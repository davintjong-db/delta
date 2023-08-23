/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.commands

// scalastyle:off import.ordering.noEmptyLine
import java.util.concurrent.TimeUnit

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.DeltaColumnMapping.{dropColumnMappingMetadata, filterColumnMappingProperties}
import org.apache.spark.sql.delta.actions.{Action, Metadata, Protocol}
import org.apache.spark.sql.delta.actions.DomainMetadata
import org.apache.spark.sql.delta.hooks.IcebergConverterHook
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, CatalogTableType}
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.connector.catalog.Identifier
import org.apache.spark.sql.execution.command.{LeafRunnableCommand, RunnableCommand}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StructType

/**
 * Single entry point for all write or declaration operations for Delta tables accessed through
 * the table name.
 *
 * @param table The table identifier for the Delta table
 * @param existingTableOpt The existing table for the same identifier if exists
 * @param mode The save mode when writing data. Relevant when the query is empty or set to Ignore
 *             with `CREATE TABLE IF NOT EXISTS`.
 * @param query The query to commit into the Delta table if it exist. This can come from
 *                - CTAS
 *                - saveAsTable
 * @param protocol This is used to create a table with specific protocol version
 */
case class CreateDeltaTableCommand(
    table: CatalogTable,
    existingTableOpt: Option[CatalogTable],
    mode: SaveMode,
    query: Option[LogicalPlan],
    operation: TableCreationModes.CreationMode = TableCreationModes.Create,
    tableByPath: Boolean = false,
    override val output: Seq[Attribute] = Nil,
    protocol: Option[Protocol] = None)
  extends LeafRunnableCommand
  with DeltaCommand
  with DeltaLogging {

  override def run(sparkSession: SparkSession): Seq[Row] = {

    assert(table.tableType != CatalogTableType.VIEW)
    assert(table.identifier.database.isDefined, "Database should've been fixed at analysis")
    // There is a subtle race condition here, where the table can be created by someone else
    // while this command is running. Nothing we can do about that though :(
    val tableExistsInCatalog = existingTableOpt.isDefined
    if (mode == SaveMode.Ignore && tableExistsInCatalog) {
      // Early exit on ignore
      return Nil
    } else if (mode == SaveMode.ErrorIfExists && tableExistsInCatalog) {
      throw DeltaErrors.tableAlreadyExists(table)
    }

    var tableWithLocation = if (tableExistsInCatalog) {
      val existingTable = existingTableOpt.get
      table.storage.locationUri match {
        case Some(location) if location.getPath != existingTable.location.getPath =>
          throw DeltaErrors.tableLocationMismatch(table, existingTable)
        case _ =>
      }
      table.copy(
        storage = existingTable.storage,
        tableType = existingTable.tableType)
    } else if (table.storage.locationUri.isEmpty) {
      // We are defining a new managed table
      assert(table.tableType == CatalogTableType.MANAGED)
      val loc = sparkSession.sessionState.catalog.defaultTablePath(table.identifier)
      table.copy(storage = table.storage.copy(locationUri = Some(loc)))
    } else {
      // 1. We are defining a new external table
      // 2. It's a managed table which already has the location populated. This can happen in DSV2
      //    CTAS flow.
      table
    }

    val tableLocation = new Path(tableWithLocation.location)
    val deltaLog = DeltaLog.forTable(sparkSession, tableLocation)

    recordDeltaOperation(deltaLog, "delta.ddl.createTable") {
      handleCommit(sparkSession, deltaLog, tableWithLocation)
    }
  }

  /**
   * Handles the transaction logic for the command. Returns the operation metrics in case of CLONE.
   */
  private def handleCommit(
      sparkSession: SparkSession,
      deltaLog: DeltaLog,
      tableWithLocation: CatalogTable): Seq[Row] = {
    val tableExistsInCatalog = existingTableOpt.isDefined
    val hadoopConf = deltaLog.newDeltaHadoopConf()
    val tableLocation = new Path(tableWithLocation.location)
    val fs = tableLocation.getFileSystem(hadoopConf)

    def checkPathEmpty(txn: OptimisticTransaction): Unit = {
      // Verify the table does not exist.
      if (mode == SaveMode.Ignore || mode == SaveMode.ErrorIfExists) {
        // We should have returned earlier in Ignore and ErrorIfExists mode if the table
        // is already registered in the catalog.
        assert(!tableExistsInCatalog)
        // Verify that the data path does not contain any data.
        // We may have failed a previous write. The retry should still succeed even if we have
        // garbage data
        if (txn.readVersion > -1 || !fs.exists(deltaLog.logPath)) {
          assertPathEmpty(hadoopConf, tableWithLocation)
        }
      }
    }

    val txn = startTxnForTableCreation(sparkSession, deltaLog, tableWithLocation)

    val result = query match {
      // CLONE handled separately from other CREATE TABLE syntax
      case Some(cmd: CloneTableCommand) =>
        checkPathEmpty(txn)
        cmd.handleClone(sparkSession, txn, targetDeltaLog = deltaLog)
      case Some(deltaWriter: WriteIntoDelta) =>
        checkPathEmpty(txn)
        handleCreateTableAsSelect(sparkSession, txn, deltaLog, deltaWriter, tableWithLocation)
        Nil
      case Some(query) =>
        checkPathEmpty(txn)
        require(!query.isInstanceOf[RunnableCommand])
        // When using V1 APIs, the `query` plan is not yet optimized, therefore, it is safe
        // to once again go through analysis
        val data = Dataset.ofRows(sparkSession, query)
        val options = new DeltaOptions(table.storage.properties, sparkSession.sessionState.conf)
        val deltaWriter = WriteIntoDelta(
          deltaLog = deltaLog,
          mode = mode,
          options,
          partitionColumns = table.partitionColumnNames,
          configuration = tableWithLocation.properties + ("comment" -> table.comment.orNull),
          data = data)
        handleCreateTableAsSelect(sparkSession, txn, deltaLog, deltaWriter, tableWithLocation)
        Nil
      case _ =>
        handleCreateTable(sparkSession, txn, tableWithLocation, fs, hadoopConf)
        Nil
    }

    runPostCommitUpdates(sparkSession, txn, deltaLog, tableWithLocation)

    result
  }

  /**
   * Runs updates post table creation commit, such as updating the catalog
   * with relevant information.
   */
  private def runPostCommitUpdates(
      sparkSession: SparkSession,
      txnUsedForCommit: OptimisticTransaction,
      deltaLog: DeltaLog,
      tableWithLocation: CatalogTable): Unit = {
    // Note that someone may have dropped and recreated the table in a separate location in the
    // meantime... Unfortunately we can't do anything there at the moment, because Hive sucks.
    logInfo(s"Table is path-based table: $tableByPath. Update catalog with mode: $operation")
    val opStartTs = TimeUnit.NANOSECONDS.toMillis(txnUsedForCommit.txnStartTimeNs)
    val postCommitSnapshot = deltaLog.update(checkIfUpdatedSinceTs = Some(opStartTs))
    val didNotChangeMetadata = txnUsedForCommit.metadata == txnUsedForCommit.snapshot.metadata
    updateCatalog(sparkSession, tableWithLocation, postCommitSnapshot, didNotChangeMetadata)


    if (UniversalFormat.icebergEnabled(postCommitSnapshot.metadata)) {
      deltaLog.icebergConverter.convertSnapshot(postCommitSnapshot, None)
    }
  }

  /**
   * Handles the transaction logic for CTAS-like statements, i.e.:
   * CREATE TABLE AS SELECT
   * CREATE OR REPLACE TABLE AS SELECT
   * .saveAsTable in DataframeWriter API
   */
  private def handleCreateTableAsSelect(
      sparkSession: SparkSession,
      txn: OptimisticTransaction,
      deltaLog: DeltaLog,
      deltaWriter: WriteIntoDelta,
      tableWithLocation: CatalogTable): Unit = {
    val isManagedTable = tableWithLocation.tableType == CatalogTableType.MANAGED
    val options = new DeltaOptions(table.storage.properties, sparkSession.sessionState.conf)

    // Execute write command for `deltaWriter` by
    //   - replacing the metadata new target table for DataFrameWriterV2 writer if it is a
    //     REPLACE or CREATE_OR_REPLACE command,
    //   - running the write procedure of DataFrameWriter command and returning the
    //     new created actions,
    //   - returning the Delta Operation type of this DataFrameWriter
    def doDeltaWrite(
        deltaWriter: WriteIntoDelta,
        schema: StructType): (Seq[Action], DeltaOperations.Operation) = {
      // In the V2 Writer, methods like "replace" and "createOrReplace" implicitly mean that
      // the metadata should be changed. This wasn't the behavior for DataFrameWriterV1.
      if (!isV1Writer) {
        replaceMetadataIfNecessary(
          txn,
          tableWithLocation,
          options,
          schema)
      }
      var actions = deltaWriter.write(
        txn,
        sparkSession
      )
      val newDomainMetadata = Seq.empty[DomainMetadata]
      if (isReplace) {
        // Ensure to remove any domain metadata for REPLACE TABLE.
        actions = actions ++ DomainMetadataUtils.handleDomainMetadataForReplaceTable(
          txn.snapshot.domainMetadata, newDomainMetadata)
      } else {
        actions = actions ++ newDomainMetadata
      }
      val op = getOperation(txn.metadata, isManagedTable, Some(options)
      )
      (actions, op)
    }

    // We are either appending/overwriting with saveAsTable or creating a new table with CTAS
    if (!hasBeenExecuted(txn, sparkSession, Some(options))) {
      val (actions, op) = doDeltaWrite(deltaWriter, deltaWriter.data.schema.asNullable)
      txn.commit(actions, op)
    }
  }

  /**
   * Handles the transaction logic for CREATE OR REPLACE TABLE statement
   * without the AS [CLONE, SELECT] clause.
   */
  private def handleCreateTable(
      sparkSession: SparkSession,
      txn: OptimisticTransaction,
      tableWithLocation: CatalogTable,
      fs: FileSystem,
      hadoopConf: Configuration): Unit = {

    val isManagedTable = tableWithLocation.tableType == CatalogTableType.MANAGED
    val tableLocation = new Path(tableWithLocation.location)
    val tableExistsInCatalog = existingTableOpt.isDefined
    val options = new DeltaOptions(table.storage.properties, sparkSession.sessionState.conf)

    def createActionsForNewTableOrVerify(): Seq[Action] = {
      if (isManagedTable) {
        // When creating a managed table, the table path should not exist or is empty, or
        // users would be surprised to see the data, or see the data directory being dropped
        // after the table is dropped.
        assertPathEmpty(hadoopConf, tableWithLocation)
      }

      // However, if we allow creating an empty schema table and indeed the table is new, we
      // would need to make sure txn.readVersion <= 0 so we are either:
      // 1) Creating a new empty schema table (version = -1) or
      // 2) Restoring an existing empty schema table at version 0. An empty schema table should
      //    not have versions > 0 because it must be written with schema changes after initial
      //    creation.
      val emptySchemaTableFlag = sparkSession.sessionState.conf
        .getConf(DeltaSQLConf.DELTA_ALLOW_CREATE_EMPTY_SCHEMA_TABLE)
      val allowRestoringExistingEmptySchemaTable =
        emptySchemaTableFlag && txn.metadata.schema.isEmpty && txn.readVersion == 0
      val allowCreatingNewEmptySchemaTable =
        emptySchemaTableFlag && tableWithLocation.schema.isEmpty && txn.readVersion == -1

      // This is either a new table, or, we never defined the schema of the table. While it is
      // unexpected that `txn.metadata.schema` to be empty when txn.readVersion >= 0, we still
      // guard against it, in case of checkpoint corruption bugs.
      val noExistingMetadata = txn.readVersion == -1 || txn.metadata.schema.isEmpty
      if (noExistingMetadata && !allowRestoringExistingEmptySchemaTable) {
        assertTableSchemaDefined(
          fs, tableLocation, tableWithLocation, sparkSession,
          allowCreatingNewEmptySchemaTable
        )
        assertPathEmpty(hadoopConf, tableWithLocation)
        // This is a user provided schema.
        // Doesn't come from a query, Follow nullability invariants.
        val newMetadata =
          getProvidedMetadata(tableWithLocation, table.schema.json)
        txn.updateMetadataForNewTable(newMetadata)
        protocol.foreach { protocol =>
          txn.updateProtocol(protocol)
        }
        Nil
      } else {
        verifyTableMetadata(txn, tableWithLocation)
        Nil
      }
    }

    // We are defining a table using the Create or Replace Table statements.
    val actionsToCommit = operation match {
      case TableCreationModes.Create =>
        require(!tableExistsInCatalog, "Can't recreate a table when it exists")
        createActionsForNewTableOrVerify()

      case TableCreationModes.CreateOrReplace if !tableExistsInCatalog =>
        // If the table doesn't exist, CREATE OR REPLACE must provide a schema
        if (tableWithLocation.schema.isEmpty) {
          throw DeltaErrors.schemaNotProvidedException
        }
        createActionsForNewTableOrVerify()
      case _ =>
        // When the operation is a REPLACE or CREATE OR REPLACE, then the schema shouldn't be
        // empty, since we'll use the entry to replace the schema
        if (tableWithLocation.schema.isEmpty) {
          throw DeltaErrors.schemaNotProvidedException
        }
        // We need to replace
        replaceMetadataIfNecessary(
          txn,
          tableWithLocation,
          options,
          tableWithLocation.schema)
        // Truncate the table
        val operationTimestamp = System.currentTimeMillis()
        var actionsToCommit = Seq.empty[Action]
        val removes = txn.filterFiles().map(_.removeWithTimestamp(operationTimestamp))
        actionsToCommit = removes
        actionsToCommit
    }

    val changedMetadata = txn.metadata != txn.snapshot.metadata
    val changedProtocol = txn.protocol != txn.snapshot.protocol
    if (actionsToCommit.nonEmpty || changedMetadata || changedProtocol) {
      val op = getOperation(txn.metadata, isManagedTable, None
      )
      txn.commit(actionsToCommit, op)
    }
  }

  private def getProvidedMetadata(table: CatalogTable, schemaString: String): Metadata = {
    Metadata(
      description = table.comment.orNull,
      schemaString = schemaString,
      partitionColumns = table.partitionColumnNames,
      configuration = table.properties,
      createdTime = Some(System.currentTimeMillis()))
  }

  private def assertPathEmpty(
      hadoopConf: Configuration,
      tableWithLocation: CatalogTable): Unit = {
    val path = new Path(tableWithLocation.location)
    val fs = path.getFileSystem(hadoopConf)
    // Verify that the table location associated with CREATE TABLE doesn't have any data. Note that
    // we intentionally diverge from this behavior w.r.t regular datasource tables (that silently
    // overwrite any previous data)
    if (fs.exists(path) && fs.listStatus(path).nonEmpty) {
      throw DeltaErrors.createTableWithNonEmptyLocation(
        tableWithLocation.identifier.toString,
        tableWithLocation.location.toString)
    }
  }

  private def assertTableSchemaDefined(
      fs: FileSystem,
      path: Path,
      table: CatalogTable,
      sparkSession: SparkSession,
      allowEmptyTableSchema: Boolean): Unit = {
    // Users did not specify the schema. We expect the schema exists in Delta.
    if (table.schema.isEmpty) {
      if (table.tableType == CatalogTableType.EXTERNAL) {
        if (fs.exists(path) && fs.listStatus(path).nonEmpty) {
          throw DeltaErrors.createExternalTableWithoutLogException(
            path, table.identifier.quotedString, sparkSession)
        } else {
          if (allowEmptyTableSchema) return
          throw DeltaErrors.createExternalTableWithoutSchemaException(
            path, table.identifier.quotedString, sparkSession)
        }
      } else {
        if (allowEmptyTableSchema) return
        throw DeltaErrors.createManagedTableWithoutSchemaException(
          table.identifier.quotedString, sparkSession)
      }
    }
  }

  /**
   * Verify against our transaction metadata that the user specified the right metadata for the
   * table.
   */
  private def verifyTableMetadata(
      txn: OptimisticTransaction,
      tableDesc: CatalogTable): Unit = {
    val existingMetadata = txn.metadata
    val path = new Path(tableDesc.location)

    // The delta log already exists. If they give any configuration, we'll make sure it all matches.
    // Otherwise we'll just go with the metadata already present in the log.
    // The schema compatibility checks will be made in `WriteIntoDelta` for CreateTable
    // with a query
    if (txn.readVersion > -1) {
      if (tableDesc.schema.nonEmpty) {
        // We check exact alignment on create table if everything is provided
        // However, if in column mapping mode, we can safely ignore the related metadata fields in
        // existing metadata because new table desc will not have related metadata assigned yet
        val differences = SchemaUtils.reportDifferences(
          dropColumnMappingMetadata(existingMetadata.schema),
          tableDesc.schema)
        if (differences.nonEmpty) {
          throw DeltaErrors.createTableWithDifferentSchemaException(
            path, tableDesc.schema, existingMetadata.schema, differences)
        }

        // If schema is specified, we must make sure the partitioning matches, even the partitioning
        // is not specified.
        if (tableDesc.partitionColumnNames != existingMetadata.partitionColumns) {
          throw DeltaErrors.createTableWithDifferentPartitioningException(
            path, tableDesc.partitionColumnNames, existingMetadata.partitionColumns)
        }
      }

      if (tableDesc.properties.nonEmpty) {
        // When comparing properties of the existing table and the new table, remove some
        // internal column mapping properties for the sake of comparison.
        val filteredTableProperties = filterColumnMappingProperties(tableDesc.properties)
        val filteredExistingProperties = filterColumnMappingProperties(
          existingMetadata.configuration)
        if (filteredTableProperties != filteredExistingProperties) {
          throw DeltaErrors.createTableWithDifferentPropertiesException(
            path, filteredTableProperties, filteredExistingProperties)
        }
        // If column mapping properties are present in both configs, verify they're the same value.
        if (!DeltaColumnMapping.verifyInternalProperties(
            tableDesc.properties, existingMetadata.configuration)) {
          throw DeltaErrors.createTableWithDifferentPropertiesException(
            path, tableDesc.properties, existingMetadata.configuration)
        }
      }
    }
  }

  /**
   * Based on the table creation operation, and parameters, we can resolve to different operations.
   * A lot of this is needed for legacy reasons in Databricks Runtime.
   * @param metadata The table metadata, which we are creating or replacing
   * @param isManagedTable Whether we are creating or replacing a managed table
   * @param options Write options, if this was a CTAS/RTAS
   */
  private def getOperation(
      metadata: Metadata,
      isManagedTable: Boolean,
      options: Option[DeltaOptions]
  ): DeltaOperations.Operation = operation match {
    // This is legacy saveAsTable behavior in Databricks Runtime
    case TableCreationModes.Create if existingTableOpt.isDefined && query.isDefined =>
      DeltaOperations.Write(mode, Option(table.partitionColumnNames), options.get.replaceWhere,
        options.flatMap(_.userMetadata)
      )

    // DataSourceV2 table creation
    // CREATE TABLE (non-DataFrameWriter API) doesn't have options syntax
    // (userMetadata uses SQLConf in this case)
    case TableCreationModes.Create =>
      DeltaOperations.CreateTable(
        metadata, isManagedTable, query.isDefined
      )

    // DataSourceV2 table replace
    // REPLACE TABLE (non-DataFrameWriter API) doesn't have options syntax
    // (userMetadata uses SQLConf in this case)
    case TableCreationModes.Replace =>
      DeltaOperations.ReplaceTable(
        metadata, isManagedTable, orCreate = false, query.isDefined
      )

    // Legacy saveAsTable with Overwrite mode
    case TableCreationModes.CreateOrReplace if options.exists(_.replaceWhere.isDefined) =>
      DeltaOperations.Write(mode, Option(table.partitionColumnNames), options.get.replaceWhere,
        options.flatMap(_.userMetadata)
      )

    // New DataSourceV2 saveAsTable with overwrite mode behavior
    case TableCreationModes.CreateOrReplace =>
      DeltaOperations.ReplaceTable(metadata, isManagedTable, orCreate = true, query.isDefined,
        options.flatMap(_.userMetadata)
      )
  }

  /**
   * Similar to getOperation, here we disambiguate the catalog alterations we need to do based
   * on the table operation, and whether we have reached here through legacy code or DataSourceV2
   * code paths.
   */
  private def updateCatalog(
      spark: SparkSession,
      table: CatalogTable,
      snapshot: Snapshot,
      didNotChangeMetadata: Boolean
    ): Unit = {
    val cleaned = cleanupTableDefinition(spark, table, snapshot)
    operation match {
      case _ if tableByPath => // do nothing with the metastore if this is by path
      case TableCreationModes.Create =>
        spark.sessionState.catalog.createTable(
          cleaned,
          ignoreIfExists = existingTableOpt.isDefined,
          validateLocation = false)
      case TableCreationModes.Replace | TableCreationModes.CreateOrReplace
          if existingTableOpt.isDefined =>
        spark.sessionState.catalog.alterTable(table)
      case TableCreationModes.Replace =>
        val ident = Identifier.of(table.identifier.database.toArray, table.identifier.table)
        throw DeltaErrors.cannotReplaceMissingTableException(ident)
      case TableCreationModes.CreateOrReplace =>
        spark.sessionState.catalog.createTable(
          cleaned,
          ignoreIfExists = false,
          validateLocation = false)
    }
  }

  /** Clean up the information we pass on to store in the catalog. */
  private def cleanupTableDefinition(spark: SparkSession, table: CatalogTable, snapshot: Snapshot)
      : CatalogTable = {
    // These actually have no effect on the usability of Delta, but feature flagging legacy
    // behavior for now
    val storageProps = if (conf.getConf(DeltaSQLConf.DELTA_LEGACY_STORE_WRITER_OPTIONS_AS_PROPS)) {
      // Legacy behavior
      table.storage
    } else {
      table.storage.copy(properties = Map.empty)
    }

    table.copy(
      schema = new StructType(),
      properties = Map.empty,
      partitionColumnNames = Nil,
      // Remove write specific options when updating the catalog
      storage = storageProps,
      tracksPartitionsInCatalog = true)
  }

  /**
   * With DataFrameWriterV2, methods like `replace()` or `createOrReplace()` mean that the
   * metadata of the table should be replaced. If overwriteSchema=false is provided with these
   * methods, then we will verify that the metadata match exactly.
   */
  private def replaceMetadataIfNecessary(
      txn: OptimisticTransaction,
      tableDesc: CatalogTable,
      options: DeltaOptions,
      schema: StructType): Unit = {
    // If a user explicitly specifies not to overwrite the schema, during a replace, we should
    // tell them that it's not supported
    val dontOverwriteSchema = options.options.contains(DeltaOptions.OVERWRITE_SCHEMA_OPTION) &&
      !options.canOverwriteSchema
    if (isReplace && dontOverwriteSchema) {
      throw DeltaErrors.illegalUsageException(DeltaOptions.OVERWRITE_SCHEMA_OPTION, "replacing")
    }
    if (txn.readVersion > -1L && isReplace && !dontOverwriteSchema) {
      // When a table already exists, and we're using the DataFrameWriterV2 API to replace
      // or createOrReplace a table, we blindly overwrite the metadata.
      val newMetadata = getProvidedMetadata(table, schema.json)
      txn.updateMetadataForNewTable(newMetadata)
    }
  }

  /**
   * Horrible hack to differentiate between DataFrameWriterV1 and V2 so that we can decide
   * what to do with table metadata. In DataFrameWriterV1, mode("overwrite").saveAsTable,
   * behaves as a CreateOrReplace table, but we have asked for "overwriteSchema" as an
   * explicit option to overwrite partitioning or schema information. With DataFrameWriterV2,
   * the behavior asked for by the user is clearer: .createOrReplace(), which means that we
   * should overwrite schema and/or partitioning. Therefore we have this hack.
   */
  private def isV1Writer: Boolean = {
    Thread.currentThread().getStackTrace.exists(_.toString.contains(
      classOf[DataFrameWriter[_]].getCanonicalName + "."))
  }

  /** Returns true if the current operation could be replacing a table. */
  private def isReplace: Boolean = {
    operation == TableCreationModes.CreateOrReplace ||
      operation == TableCreationModes.Replace
  }

  /** Returns the transaction that should be used for the CREATE/REPLACE commit. */
  private def startTxnForTableCreation(
      sparkSession: SparkSession,
      deltaLog: DeltaLog,
      tableWithLocation: CatalogTable,
      snapshotOpt: Option[Snapshot] = None): OptimisticTransaction = {
    val txn = deltaLog.startTransaction(snapshotOpt)

    // During CREATE/REPLACE, we synchronously run conversion (if Uniform is enabled) so
    // we always remove the post commit hook here.
    txn.unregisterPostCommitHooksWhere(hook => hook.name == IcebergConverterHook.name)

    txn
  }
}

// isCreate is true for Create and CreateOrReplace modes. It is false for Replace mode.
object TableCreationModes {
  sealed trait CreationMode {
    def mode: SaveMode
    def isCreate: Boolean = true
  }

  case object Create extends CreationMode {
    override def mode: SaveMode = SaveMode.ErrorIfExists
  }

  case object CreateOrReplace extends CreationMode {
    override def mode: SaveMode = SaveMode.Overwrite
  }

  case object Replace extends CreationMode {
    override def mode: SaveMode = SaveMode.Overwrite
    override def isCreate: Boolean = false
  }
}