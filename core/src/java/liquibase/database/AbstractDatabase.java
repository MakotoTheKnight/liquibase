package liquibase.database;

import liquibase.change.Change;
import liquibase.change.CheckSum;
import liquibase.change.ColumnConfig;
import liquibase.change.core.*;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.database.structure.*;
import liquibase.diff.DiffStatusListener;
import liquibase.exception.*;
import liquibase.executor.ExecutorService;
import liquibase.executor.LoggingExecutor;
import liquibase.executor.WriteExecutor;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.DatabaseSnapshotGeneratorFactory;
import liquibase.sql.Sql;
import liquibase.sql.visitor.SqlVisitor;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.*;
import liquibase.statement.UniqueConstraint;
import liquibase.statement.core.*;
import liquibase.util.ISODateFormat;
import liquibase.util.StreamUtil;
import liquibase.util.StringUtils;
import liquibase.util.log.LogFactory;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * AbstractDatabase is extended by all supported databases as a facade to the underlying database.
 * The physical connection can be retrieved from the AbstractDatabase implementation, as well as any
 * database-specific characteristics such as the datatype for "boolean" fields.
 */
public abstract class AbstractDatabase implements Database {

    private DatabaseConnection connection;
    private String defaultSchemaName;

    static final protected Logger log = LogFactory.getLogger();

    protected String currentDateTimeFunction;

    private List<RanChangeSet> ranChangeSetList;
    private static final DataType DATE_TYPE = new DataType("DATE", false);
    private static final DataType TIME_TYPE = new DataType("TIME", false);
    private static final DataType BIGINT_TYPE = new DataType("BIGINT", true);
    private static final DataType NUMBER_TYPE = new DataType("NUMBER", true);
    private static final DataType CHAR_TYPE = new DataType("CHAR", true);
    private static final DataType VARCHAR_TYPE = new DataType("VARCHAR", true);
    private static final DataType FLOAT_TYPE = new DataType("FLOAT", true);
    private static final DataType DOUBLE_TYPE = new DataType("DOUBLE", true);
    private static final DataType INT_TYPE = new DataType("INT", true);
    private static final DataType TINYINT_TYPE = new DataType("TINYINT", true);

    private static Pattern CREATE_VIEW_AS_PATTERN = Pattern.compile("^CREATE\\s+.*?VIEW\\s+.*?AS\\s+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private String databaseChangeLogTableName = System.getProperty("liquibase.databaseChangeLogTableName") == null ? "DatabaseChangeLog".toUpperCase() : System.getProperty("liquibase.databaseChangeLogTableName");
    private String databaseChangeLogLockTableName = System.getProperty("liquibase.databaseChangeLogLockTableName") == null ? "DatabaseChangeLogLock".toUpperCase() : System.getProperty("liquibase.databaseChangeLogLockTableName");

    private Integer lastChangeSetSequenceValue;

    protected AbstractDatabase() {
    }

    public DatabaseObject[] getContainingObjects() {
        return null;
    }

    // ------- DATABASE INFORMATION METHODS ---- //

    public DatabaseConnection getConnection() {
        return connection;
    }

    public void setConnection(DatabaseConnection conn) {
        this.connection = conn;
        try {
            connection.setAutoCommit(getAutoCommitMode());
        } catch (DatabaseException sqle) {
            log.warning("Can not set auto commit to " + getAutoCommitMode() + " on connection");
        }
    }

    /**
     * Auto-commit mode to run in
     */
    public boolean getAutoCommitMode() {
        return !supportsDDLInTransaction();
    }

    /**
     * By default databases should support DDL within a transaction.
     */
    public boolean supportsDDLInTransaction() {
        return true;
    }

    /**
     * Returns the name of the database product according to the underlying database.
     */
    public String getDatabaseProductName() {
        try {
            return connection.getDatabaseProductName();
        } catch (DatabaseException e) {
            throw new RuntimeException("Cannot get database name");
        }
    }

    public String getDatabaseProductName(DatabaseConnection conn) throws DatabaseException {
        try {
            return conn.getDatabaseProductName();
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }


    public String getDatabaseProductVersion() throws DatabaseException {
        try {
            return connection.getDatabaseProductVersion();
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    public int getDatabaseMajorVersion() throws DatabaseException {
        try {
            return connection.getDatabaseMajorVersion();
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    public int getDatabaseMinorVersion() throws DatabaseException {
        try {
            return connection.getDatabaseMinorVersion();
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    public String getDefaultCatalogName() throws DatabaseException {
        return null;
    }

    protected String getDefaultDatabaseSchemaName() throws DatabaseException {
        return getConnection().getConnectionUserName();
    }

    public String getDefaultSchemaName() {
        return defaultSchemaName;
    }

    public void setDefaultSchemaName(String schemaName) throws DatabaseException {
        this.defaultSchemaName = schemaName;
    }

    /**
     * Returns system (undroppable) tables and views.
     */
    protected Set<String> getSystemTablesAndViews() {
        return new HashSet<String>();
    }

    // ------- DATABASE FEATURE INFORMATION METHODS ---- //

    /**
     * Does the database type support sequence.
     */
    public boolean supportsSequences() {
        return true;
    }

    public boolean supportsAutoIncrement() {
        return true;
    }

    // ------- DATABASE-SPECIFIC SQL METHODS ---- //

    public void setCurrentDateTimeFunction(String function) {
        if (function != null) {
            this.currentDateTimeFunction = function;
        }
    }

    /**
     * Returns the database-specific datatype for the given column configuration.
     * This method will convert some generic column types (e.g. boolean, currency) to the correct type
     * for the current database.
     */
    public String getColumnType(String columnType, Boolean autoIncrement) {
        // Parse out data type and precision
        // Example cases: "CLOB", "java.sql.Types.CLOB", "CLOB(10000)", "java.sql.Types.CLOB(10000)
        String dataTypeName = null;
        String precision = null;
        if (columnType.startsWith("java.sql.Types") && columnType.contains("(")) {
            precision = columnType.substring(columnType.indexOf("(") + 1, columnType.indexOf(")"));
            dataTypeName = columnType.substring(columnType.lastIndexOf(".") + 1, columnType.indexOf("("));
        } else if (columnType.startsWith("java.sql.Types")) {
            dataTypeName = columnType.substring(columnType.lastIndexOf(".") + 1);
        } else if (columnType.contains("(")) {
            precision = columnType.substring(columnType.indexOf("(") + 1, columnType.indexOf(")"));
            dataTypeName = columnType.substring(0, columnType.indexOf("("));
        } else {
            dataTypeName = columnType;
        }

        // Translate type to database-specific type, if possible
        DataType returnTypeName = null;
        if (dataTypeName.equalsIgnoreCase("BIGINT")) {
            returnTypeName = getBigIntType();
        } else if (dataTypeName.equalsIgnoreCase("NUMBER")) {
            returnTypeName = getNumberType();
        } else if (dataTypeName.equalsIgnoreCase("BLOB")) {
            returnTypeName = getBlobType();
        } else if (dataTypeName.equalsIgnoreCase("BOOLEAN")) {
            returnTypeName = getBooleanType();
        } else if (dataTypeName.equalsIgnoreCase("CHAR")) {
            returnTypeName = getCharType();
        } else if (dataTypeName.equalsIgnoreCase("CLOB")) {
            returnTypeName = getClobType();
        } else if (dataTypeName.equalsIgnoreCase("CURRENCY")) {
            returnTypeName = getCurrencyType();
        } else if (dataTypeName.equalsIgnoreCase("DATE")) {
            returnTypeName = getDateType();
        } else if (dataTypeName.equalsIgnoreCase("DATETIME")) {
            returnTypeName = getDateTimeType();
        } else if (dataTypeName.equalsIgnoreCase("DOUBLE")) {
            returnTypeName = getDoubleType();
        } else if (dataTypeName.equalsIgnoreCase("FLOAT")) {
            returnTypeName = getFloatType();
        } else if (dataTypeName.equalsIgnoreCase("INT")) {
            returnTypeName = getIntType();
        } else if (dataTypeName.equalsIgnoreCase("INTEGER")) {
            returnTypeName = getIntType();
        } else if (dataTypeName.equalsIgnoreCase("LONGVARBINARY")) {
            returnTypeName = getBlobType();
        } else if (dataTypeName.equalsIgnoreCase("LONGVARCHAR")) {
            returnTypeName = getClobType();
        } else if (dataTypeName.equalsIgnoreCase("TEXT")) {
            returnTypeName = getClobType();
        } else if (dataTypeName.equalsIgnoreCase("TIME")) {
            returnTypeName = getTimeType();
        } else if (dataTypeName.equalsIgnoreCase("TIMESTAMP")) {
            returnTypeName = getDateTimeType();
        } else if (dataTypeName.equalsIgnoreCase("TINYINT")) {
            returnTypeName = getTinyIntType();
        } else if (dataTypeName.equalsIgnoreCase("UUID")) {
            returnTypeName = getUUIDType();
        } else if (dataTypeName.equalsIgnoreCase("VARCHAR")) {
            returnTypeName = getVarcharType();
        } else {
            if (columnType.startsWith("java.sql.Types")) {
                returnTypeName = getTypeFromMetaData(dataTypeName);
            } else {
                // Don't know what it is, just return it
                return columnType;
            }
        }

        if (returnTypeName == null) {
            throw new UnexpectedLiquibaseException("Could not determine " + dataTypeName + " for " + this.getClass().getName());
        }

        // Return type and precision, if any
        if (precision != null && returnTypeName.getSupportsPrecision()) {
            return returnTypeName.getDataTypeName() + "(" + precision + ")";
        } else {
            return returnTypeName.getDataTypeName();
        }
    }

    // Get the type from the Connection MetaData (use the MetaData to translate from java.sql.Types to DB-specific type)
    private DataType getTypeFromMetaData(final String dataTypeName) {
        return new DataType(dataTypeName, false);
//todo: reintroduce        ResultSet resultSet = null;
//        try {
//            Integer requestedType = (Integer) Class.forName("java.sql.Types").getDeclaredField(dataTypeName).get(null);
//            DatabaseConnection connection = getConnection();
//            if (connection == null) {
//                throw new RuntimeException("Cannot evaluate java.sql.Types without a connection");
//            }
//            resultSet = connection.getMetaData().getTypeInfo();
//            while (resultSet.next()) {
//                String typeName = resultSet.getString("TYPE_NAME");
//                int dataType = resultSet.getInt("DATA_TYPE");
//                int maxPrecision = resultSet.getInt("PRECISION");
//                if (requestedType == dataType) {
//                    if (maxPrecision > 0) {
//                        return new DataType(typeName, true);
//                    } else {
//                        return new DataType(typeName, false);
//                    }
//                }
//            }
//            // Connection MetaData does not contain the type, return null
//            return null;
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//            if (resultSet != null) {
//                try {
//                    resultSet.close();
//                } catch (DatabaseException e) {
//                    // Can't close result set, no handling required
//                }
//            }
//        }
    }

    public final String getColumnType(ColumnConfig columnConfig) {
        return getColumnType(columnConfig.getType(), columnConfig.isAutoIncrement());
    }

    /**
     * The database-specific value to use for "false" "boolean" columns.
     */
    public String getFalseBooleanValue() {
        return "false";
    }

    /**
     * The database-specific value to use for "true" "boolean" columns.
     */
    public String getTrueBooleanValue() {
        return "true";
    }

    /**
     * Return a date literal with the same value as a string formatted using ISO 8601.
     * <p/>
     * Note: many databases accept date literals in ISO8601 format with the 'T' replaced with
     * a space. Only databases which do not accept these strings should need to override this
     * method.
     * <p/>
     * Implementation restriction:
     * Currently, only the following subsets of ISO8601 are supported:
     * yyyy-MM-dd
     * hh:mm:ss
     * yyyy-MM-ddThh:mm:ss
     */
    public String getDateLiteral(String isoDate) {
        if (isDateOnly(isoDate) || isTimeOnly(isoDate)) {
            return "'" + isoDate + "'";
        } else if (isDateTime(isoDate)) {
//            StringBuffer val = new StringBuffer();
//            val.append("'");
//            val.append(isoDate.substring(0, 10));
//            val.append(" ");
////noinspection MagicNumber
//            val.append(isoDate.substring(11));
//            val.append("'");
//            return val.toString();
            return "'" + isoDate.replace('T', ' ') + "'";
        } else {
            return "BAD_DATE_FORMAT:" + isoDate;
        }
    }


    public String getDateLiteral(java.sql.Timestamp date) {
        return getDateLiteral(new ISODateFormat().format(date).replaceFirst("^'", "").replaceFirst("'$", ""));
    }

    public String getDateLiteral(java.sql.Date date) {
        return getDateLiteral(new ISODateFormat().format(date).replaceFirst("^'", "").replaceFirst("'$", ""));
    }

    public String getDateLiteral(java.sql.Time date) {
        return getDateLiteral(new ISODateFormat().format(date).replaceFirst("^'", "").replaceFirst("'$", ""));
    }

    public String getDateLiteral(Date date) {
        if (date instanceof java.sql.Date) {
            return getDateLiteral(((java.sql.Date) date));
        } else if (date instanceof java.sql.Time) {
            return getDateLiteral(((java.sql.Time) date));
        } else if (date instanceof java.sql.Timestamp) {
            return getDateLiteral(((java.sql.Timestamp) date));
        } else if (date instanceof ComputedDateValue) {
            return date.toString();
        } else {
            throw new RuntimeException("Unexpected type: " + date.getClass().getName());
        }
    }

    protected Date parseDate(String dateAsString) throws DateParseException {
        try {
            if (dateAsString.indexOf(" ") > 0) {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateAsString);
            } else if (dateAsString.indexOf("T") > 0) {
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(dateAsString);
            } else {
                if (dateAsString.indexOf(":") > 0) {
                    return new SimpleDateFormat("HH:mm:ss").parse(dateAsString);
                } else {
                    return new SimpleDateFormat("yyyy-MM-dd").parse(dateAsString);
                }
            }
        } catch (ParseException e) {
            throw new DateParseException(dateAsString);
        }
    }

    protected boolean isDateOnly(String isoDate) {
        return isoDate.length() == "yyyy-MM-dd".length();
    }

    protected boolean isDateTime(String isoDate) {
        return isoDate.length() >= "yyyy-MM-ddThh:mm:ss".length();
    }

    protected boolean isTimeOnly(String isoDate) {
        return isoDate.length() == "hh:mm:ss".length();
    }

    /**
     * Returns the actual database-specific data type to use a "date" (no time information) column.
     */
    public DataType getDateType() {
        return DATE_TYPE;
    }

    /**
     * Returns the actual database-specific data type to use a "time" column.
     */
    public DataType getTimeType() {
        return TIME_TYPE;
    }

    public DataType getBigIntType() {
        return BIGINT_TYPE;
    }

    public DataType getNumberType() {
        return NUMBER_TYPE;
    }

    /**
     * Returns the actual database-specific data type to use for a "char" column.
     */
    public DataType getCharType() {
        return CHAR_TYPE;
    }

    /**
     * Returns the actual database-specific data type to use for a "varchar" column.
     */
    public DataType getVarcharType() {
        return VARCHAR_TYPE;
    }

    /**
     * Returns the actual database-specific data type to use for a "float" column.
     *
     * @return database-specific type for float
     */
    public DataType getFloatType() {
        return FLOAT_TYPE;
    }

    /**
     * Returns the actual database-specific data type to use for a "double" column.
     *
     * @return database-specific type for double
     */
    public DataType getDoubleType() {
        return DOUBLE_TYPE;
    }

    /**
     * Returns the actual database-specific data type to use for a "int" column.
     *
     * @return database-specific type for int
     */
    public DataType getIntType() {
        return INT_TYPE;
    }

    /**
     * Returns the actual database-specific data type to use for a "tinyint" column.
     *
     * @return database-specific type for tinyint
     */
    public DataType getTinyIntType() {
        return TINYINT_TYPE;
    }

    /**
     * Returns database-specific line comment string.
     */
    public String getLineComment() {
        return "--";
    }

    /**
     * Returns database-specific auto-increment DDL clause.
     */
    public String getAutoIncrementClause() {
        return "AUTO_INCREMENT";
    }

    public String getConcatSql(String... values) {
        StringBuffer returnString = new StringBuffer();
        for (String value : values) {
            returnString.append(value).append(" || ");
        }

        return returnString.toString().replaceFirst(" \\|\\| $", "");
    }

// ------- DATABASECHANGELOG / DATABASECHANGELOGLOCK METHODS ---- //

    /**
     * @see liquibase.database.Database#getDatabaseChangeLogTableName()
     */
    public String getDatabaseChangeLogTableName() {
        return databaseChangeLogTableName;
    }

    /**
     * @see liquibase.database.Database#getDatabaseChangeLogLockTableName()
     */
    public String getDatabaseChangeLogLockTableName() {
        return databaseChangeLogLockTableName;
    }

    /**
     * @see liquibase.database.Database#setDatabaseChangeLogTableName(java.lang.String)
     */
    public void setDatabaseChangeLogTableName(String tableName) {
        this.databaseChangeLogTableName = tableName;
    }

    /**
     * @see liquibase.database.Database#setDatabaseChangeLogLockTableName(java.lang.String)
     */
    public void setDatabaseChangeLogLockTableName(String tableName) {
        this.databaseChangeLogLockTableName = tableName;
    }

    /**
     * This method will check the database ChangeLog table used to keep track of
     * the changes in the file. If the table does not exist it will create one
     * otherwise it will not do anything besides outputting a log message.
     */
    public void checkDatabaseChangeLogTable() throws DatabaseException {
        WriteExecutor writeExecutor = ExecutorService.getInstance().getWriteExecutor(this);
        if (!writeExecutor.executesStatements()) {
            if (((LoggingExecutor) writeExecutor).alreadyCreatedChangeTable()) {
                return;
            } else {
                ((LoggingExecutor) writeExecutor).setAlreadyCreatedChangeTable(true);
            }
        }

        DatabaseSnapshot snapShot = DatabaseSnapshotGeneratorFactory.getInstance().createSnapshot(this, getLiquibaseSchemaName(), null);
        Table changeLogTable = snapShot.getDatabaseChangeLogTable();

        List<SqlStatement> statementsToExecute = new ArrayList<SqlStatement>();

        boolean changeLogCreateAttempted = false;
        if (snapShot.hasDatabaseChangeLogTable()) {
            boolean hasDescription = changeLogTable.getColumn("DESCRIPTION") != null;
            boolean hasComments = changeLogTable.getColumn("COMMENTS") != null;
            boolean hasTag = changeLogTable.getColumn("TAG") != null;
            boolean hasLiquibase = changeLogTable.getColumn("LIQUIBASE") != null;
            boolean hasOrderExecuted = changeLogTable.getColumn("ORDEREXECUTED") != null;
            boolean checksumNotRightSize = changeLogTable.getColumn("MD5SUM").getColumnSize() != 35;

            if (!hasDescription) {
                writeExecutor.comment("Adding missing databasechangelog.description column");
                statementsToExecute.add(new AddColumnStatement(getLiquibaseSchemaName(), getDatabaseChangeLogTableName(), "DESCRIPTION", "VARCHAR(255)", null));
            }
            if (!hasTag) {
                writeExecutor.comment("Adding missing databasechangelog.tag column");
                statementsToExecute.add(new AddColumnStatement(getLiquibaseSchemaName(), getDatabaseChangeLogTableName(), "TAG", "VARCHAR(255)", null));
            }
            if (!hasComments) {
                writeExecutor.comment("Adding missing databasechangelog.comments column");
                statementsToExecute.add(new AddColumnStatement(getLiquibaseSchemaName(), getDatabaseChangeLogTableName(), "COMMENTS", "VARCHAR(255)", null));
            }
            if (!hasLiquibase) {
                writeExecutor.comment("Adding missing databasechangelog.liquibase column");
                statementsToExecute.add(new AddColumnStatement(getLiquibaseSchemaName(), getDatabaseChangeLogTableName(), "LIQUIBASE", "VARCHAR(255)", null));
            }
            if (!hasOrderExecuted) {
                writeExecutor.comment("Adding missing databasechangelog.orderexecuted column");
                statementsToExecute.add(new AddColumnStatement(getLiquibaseSchemaName(), getDatabaseChangeLogTableName(), "ORDEREXECUTED", "BIGINT", null, new NotNullConstraint(), new UniqueConstraint()));
            }
            if (checksumNotRightSize) {
                writeExecutor.comment("Modifying size of databasechangelog.md5sum column");

                ColumnConfig checksumColumn = new ColumnConfig();
                checksumColumn.setName("MD5SUM");
                checksumColumn.setType("VARCHAR(35)");
                statementsToExecute.add(new ModifyColumnsStatement(getLiquibaseSchemaName(), getDatabaseChangeLogTableName(), checksumColumn));
            }

            List<Map> md5sumRS = ExecutorService.getInstance().getReadExecutor(this).queryForList(new SelectFromDatabaseChangeLogStatement(new SelectFromDatabaseChangeLogStatement.ByNotNullCheckSum(), "MD5SUM"));
            if (md5sumRS.size() > 0) {
                String md5sum = md5sumRS.get(0).get("MD5SUM").toString();
                if (!md5sum.startsWith(CheckSum.getCurrentVersion() + ":")) {
                    writeExecutor.comment("DatabaseChangeLog checksums are an incompatible version.  Setting them to null so they will be updated on next database update");
                    statementsToExecute.add(new RawSqlStatement("UPDATE " + escapeTableName(getLiquibaseSchemaName(), getDatabaseChangeLogTableName()) + " SET MD5SUM=null"));
                }
            }


        } else if (!changeLogCreateAttempted) {
            writeExecutor.comment("Create Database Change Log Table");
            SqlStatement createTableStatement = new CreateDatabaseChangeLogTableStatement();
            if (!canCreateChangeLogTable()) {
                throw new DatabaseException("Cannot create " + escapeTableName(getDefaultSchemaName(), getDatabaseChangeLogTableName()) + " table for your database.\n\n" +
                        "Please construct it manually using the following SQL as a base and re-run LiquiBase:\n\n" +
                        createTableStatement);
            }
            // If there is no table in the database for recording change history create one.
            statementsToExecute.add(createTableStatement);
            log.info("Creating database history table with name: " + escapeTableName(getDefaultSchemaName(), getDatabaseChangeLogTableName()));
//                }
        }

        for (SqlStatement sql : statementsToExecute) {
            writeExecutor.execute(sql, new ArrayList<SqlVisitor>());
            this.commit();
        }
    }


    protected boolean canCreateChangeLogTable() throws DatabaseException {
        return true;
    }

    public boolean doesChangeLogTableExist() throws DatabaseException {
        return DatabaseSnapshotGeneratorFactory.getInstance().createSnapshot(this, getLiquibaseSchemaName(), null).hasDatabaseChangeLogTable();
    }

    public boolean doesChangeLogLockTableExist() throws DatabaseException {
        return DatabaseSnapshotGeneratorFactory.getInstance().createSnapshot(this, getLiquibaseSchemaName(), null).hasDatabaseChangeLogLockTable();
    }

    public String getLiquibaseSchemaName() {
        return getDefaultSchemaName();
    }

    /**
     * This method will check the database ChangeLogLock table used to keep track of
     * if a machine is updating the database. If the table does not exist it will create one
     * otherwise it will not do anything besides outputting a log message.
     */
    public void checkDatabaseChangeLogLockTable() throws DatabaseException {

        WriteExecutor writeExecutor = ExecutorService.getInstance().getWriteExecutor(this);
        if (!doesChangeLogLockTableExist()) {

            if (!writeExecutor.executesStatements()) {
                if (((LoggingExecutor) writeExecutor).alreadyCreatedChangeLockTable()) {
                    return;
                } else {
                    ((LoggingExecutor) writeExecutor).setAlreadyCreatedChangeLockTable(true);
                }
            }


            writeExecutor.comment("Create Database Lock Table");
            writeExecutor.execute(new CreateDatabaseChangeLogLockTableStatement(), new ArrayList<SqlVisitor>());
            this.commit();
            log.finest("Created database lock table with name: " + escapeTableName(getLiquibaseSchemaName(), getDatabaseChangeLogLockTableName()));
        }
    }

// ------- DATABASE OBJECT DROPPING METHODS ---- //

    /**
     * Drops all objects owned by the connected user.
     *
     * @param schema
     */
    public void dropDatabaseObjects(String schema) throws DatabaseException {
        try {
            DatabaseSnapshot snapshot = DatabaseSnapshotGeneratorFactory.getInstance().createSnapshot(this, schema, new HashSet<DiffStatusListener>());

            List<Change> dropChanges = new ArrayList<Change>();

            for (View view : snapshot.getViews()) {
                DropViewChange dropChange = new DropViewChange();
                dropChange.setViewName(view.getName());
                dropChange.setSchemaName(schema);

                dropChanges.add(dropChange);
            }

            for (ForeignKey fk : snapshot.getForeignKeys()) {
                DropForeignKeyConstraintChange dropFK = new DropForeignKeyConstraintChange();
                dropFK.setBaseTableSchemaName(schema);
                dropFK.setBaseTableName(fk.getForeignKeyTable().getName());
                dropFK.setConstraintName(fk.getName());

                dropChanges.add(dropFK);
            }

//            for (Index index : snapshotGenerator.getIndexes()) {
//                DropIndexChange dropChange = new DropIndexChange();
//                dropChange.setIndexName(index.getName());
//                dropChange.setSchemaName(schema);
//                dropChange.setTableName(index.getTableName());
//
//                dropChanges.add(dropChange);
//            }

            for (Table table : snapshot.getTables()) {
                DropTableChange dropChange = new DropTableChange();
                dropChange.setSchemaName(schema);
                dropChange.setTableName(table.getName());
                dropChange.setCascadeConstraints(true);

                dropChanges.add(dropChange);
            }

            if (this.supportsSequences()) {
                for (Sequence seq : snapshot.getSequences()) {
                    DropSequenceChange dropChange = new DropSequenceChange();
                    dropChange.setSequenceName(seq.getName());
                    dropChange.setSchemaName(schema);

                    dropChanges.add(dropChange);
                }
            }


            if (snapshot.hasDatabaseChangeLogTable()) {
                dropChanges.add(new AnonymousChange(new ClearDatabaseChangeLogTableStatement()));
            }

            for (Change change : dropChanges) {
                for (SqlStatement statement : change.generateStatements(this)) {
                    ExecutorService.getInstance().getWriteExecutor(this).execute(statement, new ArrayList<SqlVisitor>());
                }
            }

        } finally {
            this.commit();
        }
    }

    public boolean isSystemTable(String catalogName, String schemaName, String tableName) {
        if ("information_schema".equalsIgnoreCase(schemaName)) {
            return true;
        } else if (tableName.equalsIgnoreCase(getDatabaseChangeLogLockTableName())) {
            return true;
        } else if (getSystemTablesAndViews().contains(tableName)) {
            return true;
        }
        return false;
    }

    public boolean isSystemView(String catalogName, String schemaName, String viewName) {
        if ("information_schema".equalsIgnoreCase(schemaName)) {
            return true;
        } else if (getSystemTablesAndViews().contains(viewName)) {
            return true;
        }
        return false;
    }

    public boolean isLiquibaseTable(String tableName) {
        return tableName.equalsIgnoreCase(this.getDatabaseChangeLogTableName()) || tableName.equalsIgnoreCase(this.getDatabaseChangeLogLockTableName());
    }

// ------- DATABASE TAGGING METHODS ---- //

    /**
     * Tags the database changelog with the given string.
     */
    public void tag(String tagString) throws DatabaseException {
        WriteExecutor writeExecutor = ExecutorService.getInstance().getWriteExecutor(this);
        try {
            int totalRows = ExecutorService.getInstance().getReadExecutor(this).queryForInt(new SelectFromDatabaseChangeLogStatement("COUNT(*)"), new ArrayList<SqlVisitor>());
            if (totalRows == 0) {
                throw new DatabaseException("Cannot tag an empty database");
            }

//            Timestamp lastExecutedDate = (Timestamp) this.getWriteExecutor().queryForObject(createChangeToTagSQL(), Timestamp.class);
            int rowsUpdated = writeExecutor.update(new TagDatabaseStatement(tagString), new ArrayList<SqlVisitor>());
            if (rowsUpdated == 0) {
                throw new DatabaseException("Did not tag database change log correctly");
            }
            this.commit();
        } catch (Exception e) {
            throw new DatabaseException(e);
        }
    }

    public boolean doesTagExist(String tag) throws DatabaseException {
        int count = ExecutorService.getInstance().getReadExecutor(this).queryForInt(new SelectFromDatabaseChangeLogStatement(new SelectFromDatabaseChangeLogStatement.ByTag("tag"), "COUNT(*)"), new ArrayList<SqlVisitor>());
        return count > 0;
    }

    @Override
    public String toString() {
        if (getConnection() == null) {
            return getTypeName() + " Database";
        }

        return getConnection().getConnectionUserName() + " @ " + getConnection().getURL() + (getDefaultSchemaName() == null ? "" : " (Default Schema: " + getDefaultSchemaName() + ")");
    }


    public boolean shouldQuoteValue(String value) {
        return true;
    }

    public String getViewDefinition(String schemaName, String viewName) throws DatabaseException {
        if (schemaName == null) {
            schemaName = convertRequestedSchemaToSchema(null);
        }
        String definition = (String) ExecutorService.getInstance().getReadExecutor(this).queryForObject(new GetViewDefinitionStatement(schemaName, viewName), String.class, new ArrayList<SqlVisitor>());
        if (definition == null) {
            return null;
        }
        return CREATE_VIEW_AS_PATTERN.matcher(definition).replaceFirst("");
    }

    public int getDatabaseType(int type) {
        int returnType = type;
        if (returnType == java.sql.Types.BOOLEAN) {
            String booleanType = getBooleanType().getDataTypeName();
            if (!booleanType.equalsIgnoreCase("boolean")) {
                returnType = java.sql.Types.TINYINT;
            }
        }

        return returnType;
    }

    public Object convertDatabaseValueToJavaObject(Object defaultValue, int dataType, int columnSize, int decimalDigits) throws ParseException {
        if (defaultValue == null) {
            return null;
        } else if (defaultValue instanceof String) {
            return convertToCorrectJavaType(((String) defaultValue).replaceFirst("^'", "").replaceFirst("'$", ""), dataType, columnSize, decimalDigits);
        } else {
            return defaultValue;
        }
    }

    protected Object convertToCorrectJavaType(String value, int dataType, int columnSize, int decimalDigits) throws ParseException {
        if (value == null) {
            return null;
        }
        if (dataType == Types.CLOB || dataType == Types.VARCHAR || dataType == Types.CHAR || dataType == Types.LONGVARCHAR) {
            if (value.equalsIgnoreCase("NULL")) {
                return null;
            } else {
                return value;
            }
        }

        value = StringUtils.trimToNull(value);
        if (value == null) {
            return null;
        }

        try {
            if (dataType == Types.DATE) {
                return new java.sql.Date(parseDate(value).getTime());
            } else if (dataType == Types.TIMESTAMP) {
                return new java.sql.Timestamp(parseDate(value).getTime());
            } else if (dataType == Types.TIME) {
                return new java.sql.Time(parseDate(value).getTime());
            } else if (dataType == Types.BIGINT) {
                return new BigInteger(value);
            } else if (dataType == Types.BIT) {
                value = value.replaceFirst("b'", ""); //mysql puts wierd chars in bit field
                if (value.equalsIgnoreCase("true")) {
                    return Boolean.TRUE;
                } else if (value.equalsIgnoreCase("false")) {
                    return Boolean.FALSE;
                } else if (value.equals("1")) {
                    return Boolean.TRUE;
                } else if (value.equals("0")) {
                    return Boolean.FALSE;
                } else if (value.equals("(1)")) {
                    return Boolean.TRUE;
                } else if (value.equals("(0)")) {
                    return Boolean.FALSE;
                }
                throw new ParseException("Unknown bit value: " + value, 0);
            } else if (dataType == Types.BOOLEAN) {
                return Boolean.valueOf(value);
            } else if (dataType == Types.DECIMAL) {
                if (decimalDigits == 0) {
                    return new Integer(value);
                }
                return new Double(value);
            } else if (dataType == Types.DOUBLE || dataType == Types.NUMERIC) {
                return new Double(value);
            } else if (dataType == Types.FLOAT) {
                return new Float(value);
            } else if (dataType == Types.INTEGER) {
                return new Integer(value);
            } else if (dataType == Types.NULL) {
                return null;
            } else if (dataType == Types.REAL) {
                return new Float(value);
            } else if (dataType == Types.SMALLINT) {
                return new Integer(value);
            } else if (dataType == Types.TINYINT) {
                return new Integer(value);
            } else if (dataType == Types.BLOB) {
                return "!!!!!! LIQUIBASE CANNOT OUTPUT BLOB VALUES !!!!!!";
            } else {
                log.warning("Do not know how to convert type " + dataType);
                return value;
            }
        } catch (DateParseException e) {
            return new ComputedDateValue(value);
        } catch (NumberFormatException e) {
            return new ComputedNumericValue(value);
        }
    }

    public String convertJavaObjectToString(Object value) {
        if (value != null) {
            if (value instanceof String) {
                if ("null".equalsIgnoreCase(((String) value))) {
                    return null;
                }
                return "'" + ((String) value).replaceAll("'", "''") + "'";
            } else if (value instanceof Number) {
                return value.toString();
            } else if (value instanceof Boolean) {
                String returnValue;
                if (((Boolean) value)) {
                    returnValue = this.getTrueBooleanValue();
                } else {
                    returnValue = this.getFalseBooleanValue();
                }
                if (returnValue.matches("\\d+")) {
                    return returnValue;
                } else {
                    return "'" + returnValue + "'";
                }
            } else if (value instanceof java.sql.Date) {
                return this.getDateLiteral(((java.sql.Date) value));
            } else if (value instanceof java.sql.Time) {
                return this.getDateLiteral(((java.sql.Time) value));
            } else if (value instanceof java.sql.Timestamp) {
                return this.getDateLiteral(((java.sql.Timestamp) value));
            } else if (value instanceof ComputedDateValue) {
                return ((ComputedDateValue) value).getValue();
            } else {
                throw new RuntimeException("Unknown default value type: " + value.getClass().getName());
            }
        } else {
            return null;
        }
    }

    public String escapeTableName(String schemaName, String tableName) {
        if (schemaName == null) {
            schemaName = getDefaultSchemaName();
        }

        if (StringUtils.trimToNull(schemaName) == null || !supportsSchemas()) {
            return escapeDatabaseObject(tableName);
        } else {
            return escapeDatabaseObject(schemaName) + "." + escapeDatabaseObject(tableName);
        }
    }

    public String escapeDatabaseObject(String objectName) {
        return objectName;
    }

    public String escapeIndexName(String schemaName, String indexName) {
        if (StringUtils.trimToNull(schemaName) == null || !supportsSchemas()) {
            return escapeDatabaseObject(indexName);
        } else {
            return escapeDatabaseObject(schemaName) + "." + escapeDatabaseObject(indexName);
        }
    }

    public String escapeSequenceName(String schemaName, String sequenceName) {
        if (schemaName == null) {
            schemaName = getDefaultSchemaName();
        }

        if (StringUtils.trimToNull(schemaName) == null || !supportsSchemas()) {
            return escapeDatabaseObject(sequenceName);
        } else {
            return escapeDatabaseObject(schemaName) + "." + escapeDatabaseObject(sequenceName);
        }
    }

    public String escapeConstraintName(String constraintName) {
        return escapeDatabaseObject(constraintName);
    }

    public String escapeColumnName(String schemaName, String tableName, String columnName) {
        if (schemaName == null) {
            schemaName = getDefaultSchemaName();
        }

        return escapeDatabaseObject(columnName);
    }

    public String escapeColumnNameList(String columnNames) {
        StringBuffer sb = new StringBuffer();
        for (String columnName : columnNames.split(",")) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(escapeDatabaseObject(columnName.trim()));
        }
        return sb.toString();

    }

    public String convertRequestedSchemaToCatalog(String requestedSchema) throws DatabaseException {
        if (getDefaultCatalogName() == null) {
            return null;
        } else {
            if (requestedSchema == null) {
                return getDefaultCatalogName();
            }
            return StringUtils.trimToNull(requestedSchema);
        }
    }

    public String convertRequestedSchemaToSchema(String requestedSchema) throws DatabaseException {
        String returnSchema = requestedSchema;
        if (returnSchema == null) {
            returnSchema = getDefaultDatabaseSchemaName();
        }

        if (returnSchema != null) {
            returnSchema = returnSchema.toUpperCase();
        }
        return returnSchema;
    }

    public boolean supportsSchemas() {
        return true;
    }

    public String generatePrimaryKeyName(String tableName) {
        return "PK_" + tableName.toUpperCase();
    }

    public String escapeViewName(String schemaName, String viewName) {
        return escapeTableName(schemaName, viewName);
    }

    /**
     * Returns the run status for the given ChangeSet
     */
    public ChangeSet.RunStatus getRunStatus(ChangeSet changeSet) throws DatabaseException, DatabaseHistoryException {
        if (!doesChangeLogTableExist()) {
            return ChangeSet.RunStatus.NOT_RAN;
        }

        RanChangeSet foundRan = getRanChangeSet(changeSet);

        if (foundRan == null) {
            return ChangeSet.RunStatus.NOT_RAN;
        } else {
            if (foundRan.getLastCheckSum() == null) {
                try {
                    log.info("Updating NULL md5sum for " + changeSet.toString());
                    ExecutorService.getInstance().getWriteExecutor(this).execute(new RawSqlStatement("UPDATE " + escapeTableName(getLiquibaseSchemaName(), getDatabaseChangeLogTableName()) + " SET MD5SUM='"+changeSet.generateCheckSum().toString()+"' WHERE ID='"+changeSet.getId()+"' AND AUTHOR='"+changeSet.getAuthor()+"' AND FILENAME='"+changeSet.getFilePath()+"'"));

                    this.commit();
                } catch (DatabaseException e) {
                    throw new DatabaseException(e);
                }

                return ChangeSet.RunStatus.ALREADY_RAN;
            } else {
                if (foundRan.getLastCheckSum().equals(changeSet.generateCheckSum())) {
                    return ChangeSet.RunStatus.ALREADY_RAN;
                } else {
                    if (changeSet.shouldRunOnChange()) {
                        return ChangeSet.RunStatus.RUN_AGAIN;
                    } else {
                        return ChangeSet.RunStatus.INVALID_MD5SUM;
//                        throw new DatabaseHistoryException("MD5 Check for " + changeSet.toString() + " failed");
                    }
                }
            }
        }
    }

    public RanChangeSet getRanChangeSet(ChangeSet changeSet) throws DatabaseException, DatabaseHistoryException {
        if (!doesChangeLogTableExist()) {
            throw new DatabaseHistoryException("Database change table does not exist");
        }

        RanChangeSet foundRan = null;
        for (RanChangeSet ranChange : getRanChangeSetList()) {
            if (ranChange.isSameAs(changeSet)) {
                foundRan = ranChange;
                break;
            }
        }
        return foundRan;
    }

    /**
     * Returns the ChangeSets that have been run against the current database.
     */
    public List<RanChangeSet> getRanChangeSetList() throws DatabaseException {
        if (this.ranChangeSetList != null) {
            return this.ranChangeSetList;
        }

        String databaseChangeLogTableName = escapeTableName(getLiquibaseSchemaName(), getDatabaseChangeLogTableName());
        ranChangeSetList = new ArrayList<RanChangeSet>();
        if (doesChangeLogTableExist()) {
            log.info("Reading from " + databaseChangeLogTableName);
            SqlStatement select = new SelectFromDatabaseChangeLogStatement("FILENAME", "AUTHOR", "ID", "MD5SUM", "DATEEXECUTED", "ORDEREXECUTED", "TAG").setOrderBy("DATEEXECUTED ASC", "ORDEREXECUTED ASC");
            List<Map> results = ExecutorService.getInstance().getReadExecutor(this).queryForList(select);
            for (Map rs : results) {
                String fileName = rs.get("FILENAME").toString();
                String author = rs.get("AUTHOR").toString();
                String id = rs.get("ID").toString();
                String md5sum = rs.get("MD5SUM") == null ? null : rs.get("MD5SUM").toString();
                Date dateExecuted = (Date) rs.get("DATEEXECUTED");
                String tag = rs.get("TAG") == null ? null : rs.get("TAG").toString();
                RanChangeSet ranChangeSet = new RanChangeSet(fileName, id, author, CheckSum.parse(md5sum), dateExecuted, tag);
                ranChangeSetList.add(ranChangeSet);
            }
        }
        return ranChangeSetList;
    }

    public Date getRanDate(ChangeSet changeSet) throws DatabaseException, DatabaseHistoryException {
        RanChangeSet ranChange = getRanChangeSet(changeSet);
        if (ranChange == null) {
            return null;
        } else {
            return ranChange.getDateExecuted();
        }
    }

    /**
     * After the change set has been ran against the database this method will update the change log table
     * with the information.
     */
    public void markChangeSetAsRan(ChangeSet changeSet) throws DatabaseException {


        ExecutorService.getInstance().getWriteExecutor(this).execute(new MarkChangeSetRanStatement(changeSet, false), new ArrayList<SqlVisitor>());

        getRanChangeSetList().add(new RanChangeSet(changeSet));
    }

    public void markChangeSetAsReRan(ChangeSet changeSet) throws DatabaseException {

        ExecutorService.getInstance().getWriteExecutor(this).execute(new MarkChangeSetRanStatement(changeSet, true), new ArrayList<SqlVisitor>());
        this.commit();
    }

    public void removeRanStatus(ChangeSet changeSet) throws DatabaseException {

        ExecutorService.getInstance().getWriteExecutor(this).execute(new RemoveChangeSetRanStatusStatement(changeSet), new ArrayList<SqlVisitor>());
        commit();

        getRanChangeSetList().remove(new RanChangeSet(changeSet));
    }

    public String escapeStringForDatabase(String string) {
        return string.replaceAll("'", "''");
    }

    public void commit() throws DatabaseException {
        try {
            getConnection().commit();
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    public void rollback() throws DatabaseException {
        try {
            getConnection().rollback();
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AbstractDatabase that = (AbstractDatabase) o;

        return !(connection != null ? !connection.equals(that.connection) : that.connection != null);

    }

    @Override
    public int hashCode() {
        return (connection != null ? connection.hashCode() : 0);
    }

    public void close() throws DatabaseException {
        try {
            DatabaseConnection connection = getConnection();
            if (connection != null) {
                connection.close();
            }
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    public boolean supportsRestrictForeignKeys() {
        return true;
    }

    public boolean isAutoCommit() throws DatabaseException {
        try {
            return getConnection().getAutoCommit();
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    public void setAutoCommit(boolean b) throws DatabaseException {
        try {
            getConnection().setAutoCommit(b);
        } catch (DatabaseException e) {
            throw new DatabaseException(e);
        }
    }

    /**
     * Default implementation, just look for "local" IPs
     *
     * @throws liquibase.exception.DatabaseException
     *
     */
    public boolean isLocalDatabase() throws DatabaseException {
        DatabaseConnection connection = getConnection();
        if (connection == null) {
            return true;
        }
        String url = connection.getURL();
        return (url.indexOf("localhost") >= 0) || (url.indexOf("127.0.0.1") >= 0);
    }

    public void executeStatements(Change change, List<SqlVisitor> sqlVisitors) throws LiquibaseException, UnsupportedChangeException {
        SqlStatement[] statements = change.generateStatements(this);

        execute(statements, sqlVisitors);
    }

    /*
     * Executes the statements passed as argument to a target {@link Database}
     *
     * @param statements an array containing the SQL statements to be issued
     * @param database the target {@link Database}
     * @throws DatabaseException if there were problems issuing the statements
     */
    public void execute(SqlStatement[] statements, List<SqlVisitor> sqlVisitors) throws LiquibaseException {
        for (SqlStatement statement : statements) {
            LogFactory.getLogger().finest("Executing Statement: " + statement);
            ExecutorService.getInstance().getWriteExecutor(this).execute(statement, sqlVisitors);
        }
    }


    public void saveStatements(Change change, List<SqlVisitor> sqlVisitors, Writer writer) throws IOException, UnsupportedChangeException, StatementNotSupportedOnDatabaseException, LiquibaseException {
        SqlStatement[] statements = change.generateStatements(this);
        for (SqlStatement statement : statements) {
            for (Sql sql : SqlGeneratorFactory.getInstance().generateSql(statement, this)) {
                writer.append(sql.toSql()).append(sql.getEndDelimiter()).append(StreamUtil.getLineSeparator()).append(StreamUtil.getLineSeparator());
            }
        }
    }

    public void executeRollbackStatements(Change change, List<SqlVisitor> sqlVisitors) throws LiquibaseException, UnsupportedChangeException, RollbackImpossibleException {
        SqlStatement[] statements = change.generateRollbackStatements(this);
        execute(statements, sqlVisitors);
    }

    public void saveRollbackStatement(Change change, List<SqlVisitor> sqlVisitors, Writer writer) throws IOException, UnsupportedChangeException, RollbackImpossibleException, StatementNotSupportedOnDatabaseException, LiquibaseException {
        SqlStatement[] statements = change.generateRollbackStatements(this);
        for (SqlStatement statement : statements) {
            for (Sql sql : SqlGeneratorFactory.getInstance().generateSql(statement, this)) {
                writer.append(sql.toSql()).append(sql.getEndDelimiter()).append("\n\n");
            }
        }
    }

    public boolean isPeculiarLiquibaseSchema() {
        return false;
    }

    public int getNextChangeSetSequenceValue() throws LiquibaseException {
        if (lastChangeSetSequenceValue == null) {
            lastChangeSetSequenceValue = ExecutorService.getInstance().getReadExecutor(this).queryForInt(new GetNextChangeSetSequenceValueStatement());
        }

        return ++lastChangeSetSequenceValue;
    }
}
