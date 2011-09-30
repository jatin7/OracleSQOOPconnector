/**
 *   Copyright 2011 Quest Software, Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.quest.oraoop;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

import oracle.jdbc.driver.OracleConnection;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;

import com.cloudera.sqoop.mapreduce.db.DBConfiguration;
import com.cloudera.sqoop.mapreduce.db.DBInputFormat;
import com.cloudera.sqoop.mapreduce.db.DBInputFormat.DBInputSplit;
import com.cloudera.sqoop.mapreduce.db.DataDrivenDBRecordReader;
import com.quest.oraoop.OraOopConstants.OraOopTableImportWhereClauseLocation;
import com.quest.oraoop.OraOopUtilities.OraOopStatsReports;

/*
 * NOTES:
 * 
 * 		T is the output-type of this record reader.
 * 
 * 		getFieldNames() is overridden to insert an "data_chunk_id" column
 * 		containing the id (integer) of the Oracle data-chunk the data 
 * 		was obtained from. This is used to calculate the "percentage complete"
 *      for this mapper.
 *      
 *      getSelectQuery() is overridden to inject the actual data_chunk_id number
 *      into the query that is executed (for the data-chunk being processed).
 *      
 *      This class extends DBRecordReader. Unfortunately, DBRecordReader does 
 *      not expose its results property (of type ResultSet), so we have to
 *      override executeQuery() in order to obtain a reference to the data
 *      obtained when the SQL generated by getSelectQuery() is executed.   
 */
class OraOopDBRecordReader<T extends DBWritable> extends DataDrivenDBRecordReader<T> {

    private static final OraOopLog LOG = OraOopLogFactory.getLog(OraOopDBRecordReader.class);

    private OraOopDBInputSplit dbInputSplit;            //<- The split this record-reader is working on.
    private int numberOfBlocksInThisSplit;              //<- The number of Oracle blocks in this Oracle data-chunk.
    private int numberOfBlocksProcessedInThisSplit;     //<- How many Oracle blocks we've processed with this record-reader.
    private int currentDataChunkId = -1;                //<- The id of the current data-chunk being processed
    private ResultSet results;                          //<- The ResultSet containing the data from the query returned by getSelectQuery()
    private int columnIndexDataChunkIdZeroBased = -1;   //<- The zero-based column index of the data_chunk_id column.
    private boolean progressCalculationErrorLogged;     //<- Whether we've logged a problem with the progress calculation during nextKeyValue().
    private Object oraOopOraStats;                      //<- A reference to the Oracle statistics object that is being tracked for this Oracle session.
    private boolean profilingEnabled;                   //<- Whether to collect profiling metrics
    private long timeSpentInNextKeyValueInNanoSeconds;  //<- Total time spent in super.nextKeyValue()

    public OraOopDBRecordReader(DBInputFormat.DBInputSplit split
                               ,Class<T> inputClass
                               ,Configuration conf
                               ,Connection conn
                               ,DBConfiguration dbConfig
                               ,String cond
                               ,String[] fields
                               ,String table) throws SQLException {

        super(split, inputClass, conf, conn, dbConfig, cond, fields, table, "ORACLE-ORAOOP");

        OraOopUtilities.enableDebugLoggingIfRequired(conf);

        this.dbInputSplit = castSplit(split);

        String thisOracleInstanceName = OraOopOracleQueries.getCurrentOracleInstanceName(conn);
        LOG.info(String.format("This record reader is connected to Oracle via the JDBC URL: \n"+ 
                               "\t\"%s\"\n"+ 
                               "\tto the Oracle instance: \"%s\""
                              ,((OracleConnection) conn).getURL()
                              ,thisOracleInstanceName));

        OracleConnectionFactory.initializeOracleConnection(conn, conf);

        if (OraOopUtilities.userWantsOracleSessionStatisticsReports(conf))
            this.oraOopOraStats = OraOopUtilities.startSessionSnapshot(conn);

        this.numberOfBlocksInThisSplit = this.dbInputSplit.getTotalNumberOfBlocksInThisSplit();
        this.numberOfBlocksProcessedInThisSplit = 0;

        this.profilingEnabled = conf.getBoolean("oraoop.profiling.enabled", false);
    }

    public static OraOopDBInputSplit castSplit(DBInputSplit split) {

        // Check there's a split available...
        if (split == null)
            throw new IllegalArgumentException("The DBInputSplit cannot be null.");

        // Check that the split is the correct type...
        Class<?> desiredSplitClass = OraOopDBInputSplit.class;// this.dbInputSplit.getClass();
        if (!(split.getClass() == desiredSplitClass)) {
            String errMsg = String.format("The type of Split available within %s "+ 
                                          "should be an instance of class %s, "+ 
                                          "but is actually an instance of class %s"
                                         ,OraOopUtilities.getCurrentMethodName()
                                         ,desiredSplitClass.getName()
                                         ,split.getClass().getName());
            throw new RuntimeException(errMsg);
        }

        // TODO Cast this using desiredSplitClass, so we only need 1 line of code that
        // identifies the type of the split class...
        // inputSplit = (desiredSplitClass)this.getSplit();
        return (OraOopDBInputSplit) split;
    }

    @Override
    protected String[] getFieldNames() {

        String[] fieldNames = super.getFieldNames();
        ArrayList<String> result = new ArrayList<String>();

        for (int idx = 0; idx < fieldNames.length; idx++)
            result.add(fieldNames[idx]);

        result.add(OraOopConstants.COLUMN_NAME_DATA_CHUNK_ID);
        this.columnIndexDataChunkIdZeroBased = result.size() - 1;

        return result.toArray(new String[result.size()]);
    }

    protected String getSelectQuery() {

        StringBuilder query = new StringBuilder();

        if (this.dbInputSplit.getDataChunks() == null) {
            String errMsg = String.format("The %s does not contain any data-chunks, within %s."
                                         ,this.dbInputSplit.getClass().getName()
                                         ,OraOopUtilities.getCurrentMethodName());
            throw new RuntimeException(errMsg);
        }

        OraOopConstants.OraOopTableImportWhereClauseLocation whereClauseLocation = OraOopUtilities.getOraOopTableImportWhereClauseLocation(this.getDBConf().getConf(), OraOopConstants.OraOopTableImportWhereClauseLocation.SUBSPLIT);

        OracleTable tableContext = getOracleTableContext();
        OracleTableColumns tableColumns = null;
        try {
            
            Configuration conf = this.getDBConf().getConf();
            
            tableColumns = OraOopOracleQueries.getTableColumns(getConnection()
                                                              ,tableContext
                                                              ,OraOopUtilities.omitLobAndLongColumnsDuringImport(conf)
                                                              ,OraOopUtilities.recallSqoopJobType(conf)
                                                              ,true    //<- onlyOraOopSupportedTypes
                                                              ,true    //<- omitOraOopPseudoColumns
                                                              );            
        }
        catch (SQLException ex) {
            LOG.error(String.format("Unable to obtain the data-types of the columns in table %s.\n"+ 
                                    "Error:\n%s"
                                   ,tableContext.toString()
                                   ,ex.getMessage()));
            throw new RuntimeException(ex);
        }

        int numberOfDataChunks = this.dbInputSplit.getNumberOfDataChunks();
        for (int idx = 0; idx < numberOfDataChunks; idx++) {

            OraOopOracleDataChunk dataChunk = this.dbInputSplit.getDataChunks().get(idx);

            if (idx > 0)
                query.append("UNION ALL \n");

            query.append(getColumnNamesClause(tableColumns, dataChunk.id)) // <- SELECT clause
                 .append("\n");

            query.append(" FROM ")
                 .append(this.getTableName())
                 .append(" t")
                 .append("\n");

            query.append(" WHERE (")
                 .append(getRowIdClauseForDataChunk(this.dbInputSplit, idx))
                 .append(")\n");

            // If the user wants the WHERE clause applied to each data-chunk...
            if (whereClauseLocation == OraOopTableImportWhereClauseLocation.SUBSPLIT) {
                String conditions = this.getConditions();
                if (conditions != null && conditions.length() > 0)
                    query.append(" AND (")
                         .append(conditions)
                         .append(")\n");
            }

        }

        // If the user wants the WHERE clause applied to the whole split...
        if (whereClauseLocation == OraOopTableImportWhereClauseLocation.SPLIT) {
            String conditions = this.getConditions();
            if (conditions != null && 
                conditions.length() > 0) {

                // Insert a "select everything" line at the start of the SQL query...
                query.insert(0, getColumnNamesClause(tableColumns, -1) + " FROM (\n");

                // ...and then apply the WHERE clause to all the UNIONed sub-queries...
                query.append(")\n")
                     .append("WHERE\n")
                     .append(conditions)
                     .append("\n");
            }
        }

        LOG.info("SELECT QUERY = \n" + query.toString());

        return query.toString();
    }

    private String getColumnNamesClause(OracleTableColumns tableColumns, int dataChunkId) {

        StringBuilder result = new StringBuilder();

        result.append("SELECT ");
        result.append(OraOopUtilities.getImportHint(this.getDBConf().getConf()));

        String[] fieldNames = this.getFieldNames();

        int firstFieldIndex = 0;
        int lastFieldIndex = fieldNames.length - 1;
        for (int i = firstFieldIndex; i <= lastFieldIndex; i++) {
            if (i > firstFieldIndex)
                result.append(",");
            String fieldName = fieldNames[i];

            OracleTableColumn oracleTableColumn = tableColumns.findColumnByName(fieldName);
            if (oracleTableColumn != null) {
                if (oracleTableColumn.dataType.equals(OraOopConstants.Oracle.URITYPE))
                    fieldName = String.format("uritype.geturl(%s)"
                                             ,fieldName);
            }

            // If this field is the "data_chunk_id" that we inserted during getFields()
            // then we need to insert the value of that data_chunk_id now...
            if (i == this.columnIndexDataChunkIdZeroBased && 
                fieldName == OraOopConstants.COLUMN_NAME_DATA_CHUNK_ID)
                if (dataChunkId > -1)
                    fieldName = String.format("%d %s"
                                             ,dataChunkId
                                             ,OraOopConstants.COLUMN_NAME_DATA_CHUNK_ID);

            result.append(fieldName);
        }
        return result.toString();
    }

    private String getRowIdClauseForDataChunk(OraOopDBInputSplit split, int dataChunkIndex) {

        OraOopOracleDataChunk dataChunk = split.getDataChunks().get(dataChunkIndex);
        return String.format("(rowid >= dbms_rowid.rowid_create(%d, %d, %d, %d, %d)"
                            ,OraOopConstants.Oracle.ROWID_EXTENDED_ROWID_TYPE
                            ,dataChunk.oracleDataObjectId
                            ,dataChunk.relativeDatafileNumber
                            ,dataChunk.startBlockNumber
                            ,0) +
               String.format(" AND rowid <= dbms_rowid.rowid_create(%d, %d, %d, %d, %d))"
                            ,OraOopConstants.Oracle.ROWID_EXTENDED_ROWID_TYPE
                            ,dataChunk.oracleDataObjectId
                            ,dataChunk.relativeDatafileNumber
                            ,dataChunk.finishBlockNumber
                            ,OraOopConstants.Oracle.ROWID_MAX_ROW_NUMBER_PER_BLOCK);
    }

    /** {@inheritDoc} */
    public long getPos() throws IOException {

        // This split contains multiple data-chunks.
        // Each data-chunk contains multiple blocks.
        // Return the number of blocks that have been processed by this split...
        return numberOfBlocksProcessedInThisSplit;
    }

    /** {@inheritDoc} */
    public float getProgress() throws IOException {

        return numberOfBlocksProcessedInThisSplit / (float) numberOfBlocksInThisSplit;
    }

    @Override
    public boolean nextKeyValue() throws IOException {

        boolean result = false;
        try {

            long startTime = 0;
            if (this.profilingEnabled)
                startTime = System.nanoTime();

            result = super.nextKeyValue();

            if (this.profilingEnabled)
                this.timeSpentInNextKeyValueInNanoSeconds += System.nanoTime() - startTime;

            // Keep track of which data-chunk we're processing, and therefore how many
            // Oracle blocks we've processed. This can be used to calculate our
            // "percentage complete"...
            if (result && 
                this.results != null) {

                int thisDataChunkId = -2;
                try {
                    // ColumnIndexes are 1-based in jdbc...
                    thisDataChunkId = this.results.getInt(this.columnIndexDataChunkIdZeroBased + 1);
                }
                catch (SQLException ex) {
                    if (!progressCalculationErrorLogged) {
                        // This prevents us from flooding the log with the same message thousands of times...
                        progressCalculationErrorLogged = true;

                        LOG.warn(String.format("Unable to obtain the value of the %s column in method %s.\n"+ 
                                               "\tthis.columnIndexDataChunkIdZeroBased = %d (NB: jdbc field indexes are 1-based)\n"+ 
                                               "\tAs a consequence, progress for the record-reader cannot be calculated.\n"+ 
                                               "\tError=\n%s"
                                              ,OraOopConstants.COLUMN_NAME_DATA_CHUNK_ID
                                              ,OraOopUtilities.getCurrentMethodName()
                                              ,this.columnIndexDataChunkIdZeroBased
                                              ,ex.getMessage()));
                    }
                }

                if (this.currentDataChunkId != thisDataChunkId) {
                    if (this.currentDataChunkId != -1) {
                        OraOopOracleDataChunk dataChunk = this.dbInputSplit.findDataChunkById(thisDataChunkId);
                        if (dataChunk != null)
                            this.numberOfBlocksProcessedInThisSplit += dataChunk.getNumberOfBlocks();
                    }
                    this.currentDataChunkId = thisDataChunkId;
                }
            }
        }
        catch (IOException ex) {
            if (OraOopUtilities.oracleSessionHasBeenKilled(ex)) {
                LOG.info("\n*********************************************************"+ 
                         "\nThe Oracle session in use has been killed by a 3rd party."+ 
                         "\n*********************************************************");
            }
            throw ex;
        }

        return result;
    }

    @Override
    protected ResultSet executeQuery(String query) throws SQLException {

        try {
            this.results = super.executeQuery(query);
            return this.results;
        }
        catch (SQLException ex) {
            LOG.error(String.format("Error in %s while executing the SQL query:\n"+ 
                                    "%s\n\n"+ 
                                    "%s"
                                   ,OraOopUtilities.getCurrentMethodName()
                                   ,query
                                   ,ex.getMessage()));
            throw ex;
        }
    }

    @Override
    public void close() throws IOException {

        if (this.profilingEnabled)
            LOG.info(String.format("Time spent in super.nextKeyValue() = %s seconds."
                                  ,this.timeSpentInNextKeyValueInNanoSeconds / Math.pow(10, 9)));

        if (OraOopUtilities.userWantsOracleSessionStatisticsReports(getDBConf().getConf())) {
            OraOopStatsReports reports = OraOopUtilities.stopSessionSnapshot(this.oraOopOraStats);
            this.oraOopOraStats = null;

            LOG.info(String.format("Oracle Statistics Report for OraOop:\n\n%s"
                                  ,reports.performanceReport));

            String fileName = String.format("oracle-stats-csv-%d"
                                           ,this.dbInputSplit.splitId);
            OraOopUtilities.writeOutputFile(this.getDBConf().getConf(), fileName, reports.CsvReport);

            fileName = String.format("oracle-stats-%d"
                                    ,this.dbInputSplit.splitId);
            OraOopUtilities.writeOutputFile(this.getDBConf().getConf(), fileName, reports.performanceReport);
        }

        super.close();
    }

    public OracleTable getOracleTableContext() {

        Configuration conf = this.getDBConf().getConf();
        OracleTable result = new OracleTable(conf.get(OraOopConstants.ORAOOP_TABLE_OWNER), conf.get(OraOopConstants.ORAOOP_TABLE_NAME));
        return result;
    }

}