/**
 * Copyright (c) 2018-present, A2 Rešitve d.o.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package eu.solutions.a2.cdc.oracle;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.solutions.a2.cdc.oracle.jmx.OraCdcLogMinerMBeanServer;
import eu.solutions.a2.cdc.oracle.jmx.OraCdcLogMinerMgmt;
import eu.solutions.a2.cdc.oracle.utils.ExceptionUtils;
import eu.solutions.a2.cdc.oracle.utils.Version;

/**
 * 
 * @author averemee
 *
 */
public class OraCdcLogMinerTask extends SourceTask {

	private static final Logger LOGGER = LoggerFactory.getLogger(OraCdcLogMinerTask.class);
	private static final int WAIT_FOR_WORKER_MILLIS = 50;

	private int batchSize;
	private int pollInterval;
	private Map<String, String> partition;
	private int schemaType;
	private String topic;
	private String stateFileName;
	private OraRdbmsInfo rdbmsInfo;
	private OraCdcLogMinerMgmt metrics;
	private OraDumpDecoder odd;
	private Map<Long, OraTable> tablesInProcessing;
	private Set<Long> tablesOutOfScope;
	private Map<String, OraCdcTransaction> activeTransactions;
	private BlockingQueue<OraCdcTransaction> committedTransactions;
	private OraCdcLogMinerWorkerThread worker;
	private OraCdcTransaction transaction;
	private boolean lastStatementInTransaction = true;
	private boolean needToStoreState = false;
	private boolean useOracdcSchemas = false;
	private CountDownLatch runLatch;
	private AtomicBoolean isPollRunning;

	@Override
	public String version() {
		return Version.getVersion();
	}

	@Override
	public void start(Map<String, String> props) {
		LOGGER.info("Starting oracdc logminer source task");

		batchSize = Integer.parseInt(props.get(ParamConstants.BATCH_SIZE_PARAM));
		LOGGER.debug("batchSize = {} records.", batchSize);
		pollInterval = Integer.parseInt(props.get(ParamConstants.POLL_INTERVAL_MS_PARAM));
		LOGGER.debug("pollInterval = {} ms.", pollInterval);
		schemaType = Integer.parseInt(props.get(ParamConstants.SCHEMA_TYPE_PARAM));
		LOGGER.debug("schemaType (Integer value 1 for Debezium, 2 for Kafka STD) = {} .", schemaType);
		if (schemaType == ParamConstants.SCHEMA_TYPE_INT_KAFKA_STD) {
			topic = props.get(OraCdcSourceConnectorConfig.TOPIC_PREFIX_PARAM);
		} else {
			// ParamConstants.SCHEMA_TYPE_INT_DEBEZIUM
			topic = props.get(OraCdcSourceConnectorConfig.KAFKA_TOPIC_PARAM);
		}
		LOGGER.debug("topic set to {}.", topic);
		useOracdcSchemas = Boolean.parseBoolean(props.get(ParamConstants.ORACDC_SCHEMAS_PARAM));
		if (useOracdcSchemas) {
			LOGGER.debug("oracdc will use own schemas for Oracle NUMBER and TIMESTAMP WITH [LOCAL] TIMEZONE datatypes");
		}

		try {
			rdbmsInfo = OraRdbmsInfo.getInstance();
			odd = new OraDumpDecoder(rdbmsInfo.getDbCharset(), rdbmsInfo.getDbNCharCharset());
			final OraCdcLogMinerMBeanServer mbeanSrv = new OraCdcLogMinerMBeanServer(
												rdbmsInfo, props.get("name"));
			metrics = mbeanSrv.getMbean();
			metrics.setTask(this);

			final String sourcePartitionName = rdbmsInfo.getInstanceName() + "_" + rdbmsInfo.getHostName();
			LOGGER.debug("Source Partition {} set to {}.", sourcePartitionName, rdbmsInfo.getDbId());
			partition = Collections.singletonMap(sourcePartitionName, ((Long)rdbmsInfo.getDbId()).toString());

			final Long redoSizeThreshold;
			final Integer redoFilesCount;
			if (props.containsKey(ParamConstants.REDO_FILES_SIZE_PARAM)) {
				redoSizeThreshold = Long.parseLong(props.get(ParamConstants.REDO_FILES_SIZE_PARAM));
				redoFilesCount = null;
			} else {
				redoSizeThreshold = null;
				redoFilesCount = Integer.parseInt(props.get(ParamConstants.REDO_FILES_COUNT_PARAM));
			}

			List<String> excludeList = null;
			List<String> includeList = null;
			if (props.containsKey(ParamConstants.TABLE_EXCLUDE_PARAM)) {
				excludeList =
						Arrays.asList(props.get(ParamConstants.TABLE_EXCLUDE_PARAM).split("\\s*,\\s*"));
			}
			if (props.containsKey(ParamConstants.TABLE_INCLUDE_PARAM)) {
				includeList =
						Arrays.asList(props.get(ParamConstants.TABLE_INCLUDE_PARAM).split("\\s*,\\s*"));
			}

			final Path queuesRoot = FileSystems.getDefault().getPath(
					props.get(ParamConstants.TEMP_DIR_PARAM));

			tablesInProcessing = new ConcurrentHashMap<>();
			tablesOutOfScope = new HashSet<>();
			activeTransactions = new HashMap<>();
			committedTransactions = new LinkedBlockingQueue<>();

			boolean rewind = false;
			final long firstScn;
			String firstRsId = null;
			int firstSsn = 0;
			final boolean startScnFromProps = props.containsKey(ParamConstants.LGMNR_START_SCN_PARAM);
			stateFileName = props.get(ParamConstants.PERSISTENT_STATE_FILE_PARAM);
			final Path stateFilePath = Paths.get(stateFileName);
			if (stateFilePath.toFile().exists()) {
				// File with stored state exists
				if (startScnFromProps) {
					// a2.first.change set in parameters, ignore stored state, rename file
					firstScn = Long.parseLong(props.get(ParamConstants.LGMNR_START_SCN_PARAM));
					LOGGER.info("Ignoring last processed SCN value from stored state file {} and setting it to {} from connector properties",
							stateFileName, firstScn);
				} else {
					final long restoreStarted = System.currentTimeMillis();
					OraCdcPersistentState persistentState = OraCdcPersistentState.fromFile(stateFileName);
					LOGGER.info("Will start processing using stored persistent state file {} dated {}.",
							stateFileName,
							LocalDateTime.ofInstant(
									Instant.ofEpochMilli(persistentState.getLastOpTsMillis()), ZoneId.systemDefault()
								).format(DateTimeFormatter.ISO_DATE_TIME));
					if (rdbmsInfo.getDbId() != persistentState.getDbId()) {
						LOGGER.error("DBID from stored state file {} and from connection {} are different!",
								persistentState.getDbId(), rdbmsInfo.getDbId());
						LOGGER.error("Exiting.");
						throw new ConnectException("Unable to use stored file for database with different DBID!!!");
					}
					LOGGER.debug(persistentState.toString());
					firstScn = persistentState.getLastScn();
					firstRsId = persistentState.getLastRsId();
					firstSsn = persistentState.getLastSsn();
					if (persistentState.getCurrentTransaction() != null) {
						transaction = OraCdcTransaction.restoreFromMap(persistentState.getCurrentTransaction());
						// To prevent committedTransactions.poll() in this.poll()
						lastStatementInTransaction = false;
						LOGGER.debug("Restored current transaction {}", transaction.toString());
					}
					if (persistentState.getCommittedTransactions() != null) {
						for (int i = 0; i < persistentState.getCommittedTransactions().size(); i++) {
							final OraCdcTransaction oct = OraCdcTransaction.restoreFromMap(
									persistentState.getCommittedTransactions().get(i));
							committedTransactions.add(oct);
							LOGGER.debug("Restored committed transaction {}", oct.toString());
						}
					}
					if (persistentState.getInProgressTransactions() != null) {
						for (int i = 0; i < persistentState.getInProgressTransactions().size(); i++) {
							final OraCdcTransaction oct = OraCdcTransaction.restoreFromMap(
									persistentState.getInProgressTransactions().get(i));
							activeTransactions.put(oct.getXid(), oct);
							LOGGER.debug("Restored in progress transaction {}", oct.toString());
						}
					}
					if (persistentState.getProcessedTablesIds() != null) {
						restoreTableInfoFromDictionary(persistentState.getProcessedTablesIds());
					}
					if (persistentState.getOutOfScopeTablesIds() != null) {
						persistentState.getOutOfScopeTablesIds().forEach(combinedId -> {
							tablesOutOfScope.add(combinedId);
							if (LOGGER.isDebugEnabled()) {
								final int tableId = (int) ((long) combinedId);
								final int conId = (int) (combinedId >> 32);
								LOGGER.debug("Restored out of scope table OBJECT_ID {} from CON_ID {}", tableId, conId);
							}
						});
					}
					LOGGER.info("Restore persistent state {} ms", (System.currentTimeMillis() - restoreStarted));
					rewind = true;
				}
				final String savedStateFile = stateFileName + "." + System.currentTimeMillis(); 
				Files.copy(stateFilePath, Paths.get(savedStateFile), StandardCopyOption.REPLACE_EXISTING);
				LOGGER.info("Stored state file {} copied to {}", stateFileName, savedStateFile);
			} else {
				if (startScnFromProps) {
					firstScn = Long.parseLong(props.get(ParamConstants.LGMNR_START_SCN_PARAM));
					LOGGER.info("Using first SCN value {} from connector properties.", firstScn);
				} else {
					firstScn = OraRdbmsInfo.firstScnFromArchivedLogs(OraPoolConnectionFactory.getLogMinerConnection());
					LOGGER.info("Using min(FIRST_CHANGE#) from V$ARCHIVED_LOG = {} as first SCN value.", firstScn);
				}
			}

			worker = new OraCdcLogMinerWorkerThread(
					this,
					pollInterval,
					partition,
					firstScn,
					includeList,
					excludeList,
					redoSizeThreshold,
					redoFilesCount,
					tablesInProcessing,
					tablesOutOfScope,
					schemaType,
					useOracdcSchemas,
					topic,
					odd,
					queuesRoot,
					activeTransactions,
					committedTransactions,
					metrics);
			if (rewind) {
				worker.rewind(firstScn, firstRsId, firstSsn);
			}

		} catch (SQLException | InvalidPathException | IOException e) {
			LOGGER.error("Unable to start oracdc logminer task!");
			LOGGER.error(ExceptionUtils.getExceptionStackTrace(e));
			throw new ConnectException(e);
		}
		LOGGER.trace("Starting worker thread.");
		worker.start();
		needToStoreState = true;
		runLatch = new CountDownLatch(1);
		isPollRunning = new AtomicBoolean(false);
	}

	@Override
	public List<SourceRecord> poll() throws InterruptedException {
		LOGGER.trace("BEGIN: poll()");
		isPollRunning.set(true);
		int recordCount = 0;
		int parseTime = 0;
		List<SourceRecord> result = new ArrayList<>();
		if (runLatch.getCount() < 1) {
			LOGGER.trace("Returning from poll() -> processing stopped");
			isPollRunning.set(false);
			return result;
		}
		while (recordCount < batchSize) {
			if (lastStatementInTransaction) {
				// End of transaction, need to poll new
				transaction = committedTransactions.poll();
			}
			if (transaction == null) {
				// No more records produced by LogMiner worker
				break;
			} else {
				// Prepare records...
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Start of processing transaction XID {}, first change {}, commit SCN {}.",
							transaction.getXid(), transaction.getFirstChange(), transaction.getCommitScn());
				}
				lastStatementInTransaction = false;
				boolean processTransaction = true;
				do {
					OraCdcLogMinerStatement stmt = new OraCdcLogMinerStatement();
					processTransaction = transaction.getStatement(stmt);
					lastStatementInTransaction = !processTransaction;

					if (processTransaction) {
						final OraTable oraTable = tablesInProcessing.get(stmt.getTableId());
						if (oraTable == null) {
							LOGGER.error("Strange consistency issue for DATA_OBJ# {}. Exiting.", stmt.getTableId());
							isPollRunning.set(false);
							throw new ConnectException("Strange consistency issue!!!");
						} else {
							try {

								final long startParseTs = System.currentTimeMillis();
								SourceRecord record = oraTable.parseRedoRecord(stmt);
								result.add(record);
								recordCount++;
								parseTime += (System.currentTimeMillis() - startParseTs);
							} catch (SQLException e) {
								LOGGER.error(e.getMessage());
								LOGGER.error(ExceptionUtils.getExceptionStackTrace(e));
								isPollRunning.set(false);
								throw new ConnectException(e);
							}
						}
					}
				} while (processTransaction && recordCount < batchSize);
				if (lastStatementInTransaction) {
					// close Cronicle queue only when all statements are processed
					if (LOGGER.isDebugEnabled()) {
						LOGGER.debug("End of processing transaction XID {}, first change {}, commit SCN {}.",
								transaction.getXid(), transaction.getFirstChange(), transaction.getCommitScn());
					}
					transaction.close();
					transaction = null;
				}
			}
		}
		if (recordCount == 0) {
			synchronized (this) {
				LOGGER.debug("Waiting {} ms", pollInterval);
				this.wait(pollInterval);
			}
		} else {
			metrics.addSentRecords(result.size(), parseTime);
		}
		isPollRunning.set(false);
		LOGGER.trace("END: poll()");
		return result;
	}

	@Override
	public void stop() {
		stop(true);
	}

	public void stop(boolean stopWorker) {
		LOGGER.info("Stopping oracdc logminer source task.");
		runLatch.countDown();
		if (stopWorker) {
			worker.shutdown();
			while (worker.isRunning()) {
				try {
					LOGGER.debug("Waiting {} ms for worker thread to stop...", WAIT_FOR_WORKER_MILLIS);
					Thread.sleep(WAIT_FOR_WORKER_MILLIS);
				} catch (InterruptedException e) {
					LOGGER.error(ExceptionUtils.getExceptionStackTrace(e));
				}
			}
		} else {
			while (isPollRunning.get()) {
				try {
					LOGGER.debug("Waiting {} ms for connector task to stop...", WAIT_FOR_WORKER_MILLIS);
					Thread.sleep(WAIT_FOR_WORKER_MILLIS);
				} catch (InterruptedException e) {
					LOGGER.error(ExceptionUtils.getExceptionStackTrace(e));
				}
			}
		}
		if (needToStoreState) {
			try {
				saveState(true);
			} catch(IOException ioe) {
				LOGGER.error("Unable to save state to file " + stateFileName + "!");
				LOGGER.error(ExceptionUtils.getExceptionStackTrace(ioe));
				throw new ConnectException("Unable to save state to file " + stateFileName + "!");
		}

		} else {
			LOGGER.info("Do not need to run store state procedures.");
			LOGGER.info("Check Connect log files for errors.");
		}
	}

	/**
	 * 
	 * @param saveFinalState     when set to true performs full save, when set to false only
	 *                           in-progress transactions are saved
	 * @throws IOException
	 */
	public void saveState(boolean saveFinalState) throws IOException {
		final long saveStarted = System.currentTimeMillis();
		final String fileName = saveFinalState ?
				stateFileName : (stateFileName + "-jmx-" + System.currentTimeMillis());
		LOGGER.info("Saving oracdc state to {} file...", fileName);
		OraCdcPersistentState ops = new OraCdcPersistentState();
		ops.setDbId(rdbmsInfo.getDbId());
		ops.setInstanceName(rdbmsInfo.getInstanceName());
		ops.setHostName(rdbmsInfo.getHostName());
		ops.setLastOpTsMillis(System.currentTimeMillis());
		ops.setLastScn(worker.getLastScn());
		ops.setLastRsId(worker.getLastRsId());
		ops.setLastSsn(worker.getLastSsn());
		if (saveFinalState) {
			if (transaction != null) {
				ops.setCurrentTransaction(transaction.attrsAsMap());
				LOGGER.debug("Added to state file transaction {}", transaction.toString());
			}
			if (!committedTransactions.isEmpty()) {
				final List<Map<String, Object>> committed = new ArrayList<>();
				committedTransactions.stream().forEach(trans -> {
					committed.add(trans.attrsAsMap());
					LOGGER.debug("Added to state file committed transaction {}", trans.toString());
				});
				ops.setCommittedTransactions(committed);
			}
		}
		if (!activeTransactions.isEmpty()) {
			final List<Map<String, Object>> wip = new ArrayList<>();
			activeTransactions.forEach((xid, trans) -> {
				wip.add(trans.attrsAsMap());
				LOGGER.debug("Added to state file in progress transaction {}", trans.toString());
			});
			ops.setInProgressTransactions(wip);
		}
		if (!tablesInProcessing.isEmpty()) {
			final List<Long> wipTables = new ArrayList<>();
			tablesInProcessing.forEach((combinedId, table) -> {
				wipTables.add(combinedId);
				if (LOGGER.isDebugEnabled()) {
					final int tableId = (int) ((long) combinedId);
					final int conId = (int) (combinedId >> 32);
					LOGGER.debug("Added to state file in process table OBJECT_ID {} from CON_ID {}", tableId, conId);
				}
			});
			ops.setProcessedTablesIds(wipTables);
		}
		if (!tablesOutOfScope.isEmpty()) {
			final List<Long> oosTables = new ArrayList<>();
			tablesOutOfScope.forEach(combinedId -> {
				oosTables.add(combinedId);
				metrics.addTableOutOfScope();
				if (LOGGER.isDebugEnabled()) {
					final int tableId = (int) ((long) combinedId);
					final int conId = (int) (combinedId >> 32);
					LOGGER.debug("Added to state file in out of scope table OBJECT_ID {} from CON_ID {}", tableId, conId);
				}
			});
			ops.setOutOfScopeTablesIds(oosTables);
		}
		try {
			ops.toFile(fileName);
		} catch (Exception e) {
			LOGGER.error("Unable to save state file with contents:\n{}", ops.toString());
			throw new IOException(e);
		}
		LOGGER.info("oracdc state saved to {} file, elapsed {} ms",
				fileName, (System.currentTimeMillis() - saveStarted));
		LOGGER.debug("State file contents:\n{}", ops.toString());
	}

	private void restoreTableInfoFromDictionary(List<Long> processedTablesIds) throws SQLException {
		//TODO
		//TODO What about storing structure in JSON ???
		//TODO Same code as in WorkerThread - require serious improvement!!!
		//TODO
		final Connection connection = OraPoolConnectionFactory.getConnection();
		final PreparedStatement psCheckTable;
		final boolean isCdb = rdbmsInfo.isCdb();
		if (isCdb) {
			psCheckTable = connection.prepareStatement(OraDictSqlTexts.CHECK_TABLE_CDB,
						ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		} else {
			psCheckTable = connection.prepareStatement(OraDictSqlTexts.CHECK_TABLE_NON_CDB,
						ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		}
		for (long combinedDataObjectId : processedTablesIds) {
			final int tableId = (int) combinedDataObjectId;
			final int conId = (int) (combinedDataObjectId >> 32);
			psCheckTable.setInt(1, tableId);
			if (isCdb) {
				psCheckTable.setInt(2, conId);
			}
			LOGGER.debug("Adding from database dictionary for internal id {}: OBJECT_ID = {}, CON_ID = {}",
					combinedDataObjectId, tableId, conId);
			final ResultSet rsCheckTable = psCheckTable.executeQuery();
			if (rsCheckTable.next()) {
				final String tableName = rsCheckTable.getString("TABLE_NAME");
				final String tableOwner = rsCheckTable.getString("OWNER");
				final String tableFqn = tableOwner + "." + tableName;
				final String tableTopic;
				if (schemaType == ParamConstants.SCHEMA_TYPE_INT_KAFKA_STD) {
					if (topic == null || "".equals(topic)) {
						tableTopic = tableName;
					} else {
						tableTopic = topic + "_" + tableName;
					}
				} else {
					// ParamConstants.SCHEMA_TYPE_INT_DEBEZIUM
					tableTopic = topic;
				}
				if (isCdb) {
					final String pdbName = rsCheckTable.getString("PDB_NAME");
					OraTable oraTable = new OraTable(
							pdbName, (short) conId, tableOwner, tableName,
							schemaType, useOracdcSchemas, isCdb, odd, partition, tableTopic);
						tablesInProcessing.put(combinedDataObjectId, oraTable);
						metrics.addTableInProcessing(pdbName + ":" + tableFqn);
				} else {
					OraTable oraTable = new OraTable(
						null, null, tableOwner, tableName,
						schemaType, useOracdcSchemas, isCdb, odd, partition, tableTopic);
					tablesInProcessing.put(combinedDataObjectId, oraTable);
					metrics.addTableInProcessing(tableFqn);
				}
				LOGGER.debug("Restored metadata for table {}, OBJECT_ID={}, CON_ID={}",
						tableFqn, tableId, conId);
			} else {
				throw new SQLException("Data corruption detected!\n" +
						"OBJECT_ID=" + tableId + ", CON_ID=" + conId + 
						" exist in stored state but not in database!!!");
			}
			rsCheckTable.close();
			psCheckTable.clearParameters();
			
		}
		psCheckTable.close();
	}

}