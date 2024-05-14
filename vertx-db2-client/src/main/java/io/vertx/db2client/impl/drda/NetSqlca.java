/*
 * Copyright (C) 2019,2020 IBM Corporation
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
package io.vertx.db2client.impl.drda;

import java.util.Arrays;

import io.vertx.db2client.DB2Exception;

/**
 * A SQLCA stands for "SQL Communication Area"
 * The primary purpose is for tracking the SQLCode.
 * If the code is < 0, there is an error
 * If the code is > 0, there is a warning
 * If the code is == 0, nothing is wrong
 */
public class NetSqlca {

  private static final String SQLERRMC_MESSAGE_DELIMITER = new String(new char[] {(char)20,(char)20,(char)20,(char)20});

    // Indexes into sqlErrd_
    private  static  final   int HIGH_ORDER_ROW_COUNT = 0;
    private  static  final   int LOW_ORDER_ROW_COUNT = 1;
    private  static  final   int LOW_ORDER_UPDATE_COUNT = 2;
    private  static  final   int HIGH_ORDER_UPDATE_COUNT = 3;
    public  static  final   int SQL_ERR_LENGTH = 6;
    boolean messageTextRetrievedContainsTokensOnly_ = true;

    // data corresponding to SQLCA fields
    protected int sqlCode_;        // SQLCODE
    /** A string representation of <code>sqlErrmcBytes_</code>. */
    private String sqlErrmc_;
    /** Array of errmc strings for each message in the chain. */
    private String[] sqlErrmcMessages_;
    /** SQL states for all the messages in the exception chain. */
    private String[] sqlStates_;
    // contain an error token
    private String sqlErrp_;        // function name issuing error
    protected int[] sqlErrd_;        // 6 diagnostic Information
    private String sqlWarn_;        // 11 warning Flags
    protected String sqlState_;       // SQLSTATE

    // raw sqlca data fields before unicode conversion
    protected byte[] sqlErrmcBytes_; // SQL Error Message / Reason Code
    protected byte[] sqlErrpBytes_; // SQL Error Process
    protected byte[] sqlWarnBytes_;

    private boolean containsSqlcax_ = true;
    private long rowsetRowCount_;

    // JDK stack trace calls e.getMessage(), so we must set some state on the sqlca that says return tokens only.
    private boolean returnTokensOnlyInMessageText_ = false;

    /** Cached error messages (to prevent multiple invocations of the stored
     * procedure to get the same message). */
    private String[] cachedMessages;

    NetSqlca(int sqlCode,
            String sqlState,
            byte[] sqlErrpBytes) {
       sqlCode_ = sqlCode;
       sqlState_ = sqlState;
       sqlErrpBytes_ = sqlErrpBytes;
   }

   NetSqlca(int sqlCode,
           byte[] sqlState,
           byte[] sqlErrpBytes) {
      sqlCode_ = sqlCode;
      sqlState_ = bytes2String(sqlState,0,sqlState.length);
      sqlErrpBytes_ = sqlErrpBytes;
   }

   /**
    * Checks the SQL code of the SQLCA. Possible values are documented at:
    * https://www.ibm.com/support/knowledgecenter/SSEPEK_11.0.0/codes/src/tpc/db2z_n.html
    * @param sqlca The SQL Communication Area to complete
    * @return The sqlcode
    */
   public static int complete(NetSqlca sqlca, int... allowedCodes) {
       if (sqlca == null || sqlca.sqlCode_ == 0)
           return 0;
       boolean allowed = Arrays.stream(allowedCodes).anyMatch(code -> code == sqlca.sqlCode_);
       if (!allowed && sqlca.sqlCode_ < 0) {
         throwSqlError(sqlca);
       }
       if (!allowed && sqlca.sqlCode_ > 0) {
           // TODO: Logging for DRDA layer
           System.out.println("WARNING sqlcode=" + sqlca.sqlCode_);
       }
       return sqlca.sqlCode_;
   }

   /**
    * Throws a specific error message based on the passed in SQL error code
    * @param sqlca
    */
   public static void throwSqlError(NetSqlca sqlca) {
     if (sqlca == null || sqlca.sqlCode_ == 0) {
           return;
     }

     // Force population of the sqlErrmc
     String errMsg = sqlca.getSqlErrmc();
     if (errMsg == null || errMsg.trim().length() == 0) {
       errMsg = "";
     }
     String[] errMsgTokens = errMsg.split("" + ((char) 0xFFFD));

     switch(sqlca.sqlCode_) {
            case SqlCode.INVALID_SQL_STATEMENT:
                if (errMsg.length() > 0 && errMsgTokens.length > 1)
                  throw new DB2Exception("The SQL syntax provided was invalid at or near the tokens: " + Arrays.toString(errMsgTokens), sqlca.sqlCode_, sqlca.sqlState_);
                else
                  throw new DB2Exception("The SQL syntax provided was invalid", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.MISMATCHING_COLUMNS_AND_VALUES:
                throw new DB2Exception("An INSERT statement contained a different number of insert columns from the number of insert values that were supplied", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.REPEATED_COLUMN_REFERENCE:
                if (errMsg.length() > 0)
                    throw new DB2Exception("An INSERT or UPDATE statement listed the same column name (" + errMsg + ") more than one time in its update list", sqlca.sqlCode_, sqlca.sqlState_);
                else
                    throw new DB2Exception("An INSERT or UPDATE statement listed the same column name more than one time in its update list", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.NO_ORDER_BY_IDENTIFIER:
                throw new DB2Exception("The ORDER BY clause in the statement contains a column number that is either less than one or greater than the number of columns of the result table (the number of items in the SELECT clause)", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.UPDATE_READONLY_COLUMN:
                throw new DB2Exception("An UPDATE statement referenced a column that is marked in the catalog as non-updateable.", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.INVALID_KEYWORD:
                if (errMsg.length() > 0)
                  throw new DB2Exception("The keyword, '" + errMsg + "' is not valid", sqlca.sqlCode_, sqlca.sqlState_);
                else
                  throw new DB2Exception("The keyword is not valid", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.AMBIGUOUS_COLUMN_NAME:
                if (errMsg.length() > 0)
                  throw new DB2Exception("A reference to the column name '" + errMsg + "' is ambiguous", sqlca.sqlCode_, sqlca.sqlState_);
                else
                  throw new DB2Exception("A reference to a column name is ambiguous", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.OBJECT_NOT_DEFINED:
              if (errMsg.length() > 0)
                throw new DB2Exception("The object '" + errMsg + "' provided is not defined", sqlca.sqlCode_, sqlca.sqlState_);
              else
                throw new DB2Exception("An object provided is not defined", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.COLUMN_DOES_NOT_EXIST:
              if (errMsg.length() > 0)
                throw new DB2Exception("The column '" + errMsg + "' provided does not exist", sqlca.sqlCode_, sqlca.sqlState_);
              else
                throw new DB2Exception("A column provided does not exist", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.INCORRECT_NUM_HOST_VARIABLES:
              throw new DB2Exception("The number of variables that were passed into an OPEN or EXECUTE statement do not match " +
                                     "the number of parameter markers that were defined in the statement.", sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.NULL_CONSTRAINT_VIOLATION:
                throw new DB2Exception("An attempt was made to INSERT or UPDATE a column that was declared as not nullable with the NULL value: " + errMsg,
                    sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.PRIMARY_KEY_CAN_BE_NULL:
                if (errMsg.length() > 0)
                  throw new DB2Exception("The column '" + errMsg + "' cannot be a column of a primary key because it can contain null values",
                      sqlca.sqlCode_, sqlca.sqlState_);
                else
                    throw new DB2Exception("A column could not be created as a primary key column because it can contain null values",
                        sqlca.sqlCode_, sqlca.sqlState_);
            case SqlCode.OBJECT_ALREADY_EXISTS: // -601
                if (errMsg.length() > 0) {
                    if (errMsgTokens.length == 2) {
                      String objectType = errMsgTokens[1];
                      String objectName = errMsgTokens[0];
                      throw new DB2Exception("The object with the name '" + objectName + "' of type '" + objectType + "' already exists", sqlca.sqlCode_, sqlca.sqlState_);
                    } else {
                      throw new DB2Exception("The object with the name '" + errMsg + "' already exists", sqlca.sqlCode_, sqlca.sqlState_);
                    }
                } else {
                    throw new DB2Exception("The name of the object to be created cannot be identical to the existing name", sqlca.sqlCode_, sqlca.sqlState_);
                }
           case SqlCode.DATABASE_NOT_FOUND:
             if (errMsg.length() > 0)
               throw new DB2Exception("The database '" + errMsg + "' provided was not found", sqlca.sqlCode_, sqlca.sqlState_);
             else
               throw new DB2Exception("The database provided was not found", sqlca.sqlCode_, sqlca.sqlState_);
           case SqlCode.DATA_TYPE_INVALID_ATTR:
               if (errMsg.length() > 0)
                   throw new DB2Exception("The statement cannot be processed. The data type definition '" + errMsg +
                       "' specifies an invalid attribute such as precision, length, or scale.", sqlca.sqlCode_, sqlca.sqlState_);
               else
                   throw new DB2Exception("The statement cannot be processed. A data type definition specifies an invalid attribute such as precision, length, or scale.", sqlca.sqlCode_, sqlca.sqlState_);
           case SqlCode.INSERT_INTO_GENERATED_ALWAYS:
               if (errMsg.length() > 0)
                   throw new DB2Exception("A value cannot be specified for column '" + errMsg + "' which is identified as GENERATED ALWAYS", sqlca.sqlCode_, sqlca.sqlState_);
               else
                   throw new DB2Exception("A value cannot be specified for a column which is identified as GENERATED ALWAYS", sqlca.sqlCode_, sqlca.sqlState_);
           case SqlCode.DUPLICATE_KEYS_DETECTED:
               if (errMsgTokens.length >= 2)
                   throw new DB2Exception("Duplicate keys were detected on table " + errMsgTokens[1], sqlca.sqlCode_, sqlca.sqlState_);
               else
                   throw new DB2Exception("Duplicate keys were detected on the updated table", sqlca.sqlCode_, sqlca.sqlState_);
            default:
                throw new DB2Exception(sqlca.sqlCode_, sqlca.sqlState_);
     }
   }

   void setSqlerrd(int[] sqlErrd) {
       sqlErrd_ = sqlErrd;
   }

   void setSqlwarnBytes(byte[] sqlWarnBytes) {
       sqlWarnBytes_ = sqlWarnBytes;
   }

   void setSqlerrmcBytes(byte[] sqlErrmcBytes) {
       sqlErrmcBytes_ = sqlErrmcBytes;
   }

    void returnTokensOnlyInMessageText(boolean returnTokensOnlyInMessageText) {
        returnTokensOnlyInMessageText_ = returnTokensOnlyInMessageText;
    }

    /**
     * Returns the number of messages this SQLCA contains.
     *
     * @return number of messages
     */
    synchronized int numberOfMessages() {
        initSqlErrmcMessages();
        if (sqlErrmcMessages_ != null) {
            return sqlErrmcMessages_.length;
        }
        // even if we don't have an array of errmc messages, we are able to get
        // one message out of this sqlca (although it's not very readable)
        return 1;
    }

    synchronized public int getSqlCode() {
        return sqlCode_;
    }

//    /**
//     * <p>
//     * Get the error code based on the SQL code received from the server.
//     * </p>
//     *
//     * <p>
//     * The conversion from SQL code to error code happens like this:
//     * </p>
//     *
//     * <ul>
//     * <li>If the SQL code is 0, there is no error code because the Sqlca
//     * doesn't represent an error. Return 0.</li>
//     * <li>If the SQL code is positive, the Sqlca represents a warning, and
//     * the SQL code represents the actual error code. Return the SQL code.</li>
//     * <li>If the SQL code is negative, the Sqlca represents an error, and
//     * the error code is {@code -(sqlCode+1)}.</li>
//     * </ul>
//     */
//    public synchronized int getErrorCode() {
//        // Warning or other non-error, return SQL code.
//        if (sqlCode_ >= 0) return sqlCode_;
//
//        // Negative SQL code means it is an error. Transform into a positive
//        // error code.
//        int errorCode = -(sqlCode_ + 1);
//
//        // In auto-commit mode, the embedded driver promotes statement
//        // severity to transaction severity. Do the same here to match.
//        if (errorCode == ExceptionSeverity.STATEMENT_SEVERITY &&
//                connection_ != null && connection_.autoCommit_) {
//            errorCode = ExceptionSeverity.TRANSACTION_SEVERITY;
//        }
//
//        return errorCode;
//    }

    synchronized public String getSqlErrmc() {
        if (sqlErrmc_ != null) {
            return sqlErrmc_;
        }

        // sqlErrmc string is dependent on sqlErrmcMessages_ array having
        // been built
        initSqlErrmcMessages();

        // sqlErrmc will be built only if sqlErrmcMessages_ has been built.
        // Otherwise, a null string will be returned.
        if (sqlErrmcMessages_ == null) {
            return null;
        }

        // create 0-length String if no tokens
        if (sqlErrmcMessages_.length == 0) {
            sqlErrmc_ = "";
            return sqlErrmc_;
        }

        // concatenate tokens with sqlErrmcDelimiter delimiters into one String
        StringBuffer buffer = new StringBuffer();
        int indx;
        for (indx = 0; indx < sqlErrmcMessages_.length - 1; indx++) {
            buffer.append(sqlErrmcMessages_[indx]);
            buffer.append(SQLERRMC_MESSAGE_DELIMITER);
            // all but the first message should be preceded by the SQL state
            // and a colon (see DRDAConnThread.buildTokenizedSqlerrmc() on the
            // server)
            buffer.append(sqlStates_[indx+1]);
            buffer.append(":");
        }
        // add the last token
        buffer.append(sqlErrmcMessages_[indx]);

        // save as a string
        sqlErrmc_ = buffer.toString();
        return sqlErrmc_;
    }

    /**
     * Initialize and build the arrays <code>sqlErrmcMessages_</code> and
     * <code>sqlStates_</code>.
     */
    private void initSqlErrmcMessages() {
        if (sqlErrmcMessages_ == null || sqlStates_ == null) {
            // processSqlErrmcTokens handles null sqlErrmcBytes_ case
            processSqlErrmcTokens(sqlErrmcBytes_);
        }
    }

    synchronized public String getSqlErrp() {
        if (sqlErrp_ != null) {
            return sqlErrp_;
        }

        if (sqlErrpBytes_ == null) {
            return null;
        }

        sqlErrp_ = bytes2String(sqlErrpBytes_, 0, sqlErrpBytes_.length);
        return sqlErrp_;
    }

    private int[] getSqlErrd() {
        if (sqlErrd_ != null) {
            return sqlErrd_;
        }

        sqlErrd_ = new int[ SQL_ERR_LENGTH ]; // create an int array.
        return sqlErrd_;
    }

//    String formatSqlErrd() {
//        return Utils.getStringFromInts(getSqlErrd());
//    }

    private final static String elevenBlanks = "           ";

    synchronized public String getSqlWarn() {
        if (sqlWarn_ == null) {
            if (sqlWarnBytes_ != null) {
                sqlWarn_ = bytes2String(sqlWarnBytes_, 0, sqlWarnBytes_.length);
            } else {
                sqlWarn_ = elevenBlanks;
            }
        }
        return sqlWarn_;
    }

    synchronized public String getSqlState() {
        return sqlState_;
    }

    /**
     * Get the SQL state for a given error.
     *
     * @param messageNumber the error to retrieve SQL state for
     * @return SQL state for the error
     */
    synchronized String getSqlState(int messageNumber) {
        initSqlErrmcMessages();
        if (sqlStates_ != null) {
            return sqlStates_[messageNumber];
        }
        return getSqlState();
    }

//    public Object [] getArgs(int messageNumber) {
//        if (sqlErrmcMessages_ != null)
//        return MessageUtils.getArgs(getSqlState(messageNumber),
//                                        sqlErrmcMessages_[messageNumber] );
//        return null;
//    }

//    // Gets the formatted message, can throw an exception.
//    private String getMessage(int messageNumber) throws SqlException {
//        // should this be traced to see if we are calling a stored proc?
//        if (cachedMessages != null && cachedMessages[messageNumber] != null) {
//            return cachedMessages[messageNumber];
//        }
//
//        if (connection_ == null || connection_.isClosedX() || returnTokensOnlyInMessageText_) {
//            return getUnformattedMessage(messageNumber);
//        }
//
//        ClientCallableStatement cs = null;
//        synchronized (connection_) {
//            try {
//                cs = connection_.prepareMessageProc("call SYSIBM.SQLCAMESSAGE(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
//                // Cannot let this statement commit the transaction. Otherwise,
//                // calling getWarnings while navigating a ResultSet will
//                // release and invalidate locators used by the cursor.
//                cs.isAutoCommittableStatement_ = false;
//                String errmc = null;
//                String sqlState = null;
//
//                if (sqlErrmcMessages_ != null) {
//                    errmc = sqlErrmcMessages_[messageNumber];
//                    sqlState = sqlStates_[messageNumber];
//                }
//
//                // SQLCode: SQL return code.
//                cs.setIntX(1, (messageNumber == 0) ? getSqlCode() : 0);
//                // SQLErrml: Length of SQL error message tokens.
//                cs.setShortX(2, (short) ((errmc == null) ? 0 : errmc.length()));
//                // SQLErrmc: SQL error message tokens as a String
//                cs.setStringX(3, errmc);
//                // SQLErrp: Product signature.
//                cs.setStringX(4, getSqlErrp());
//                // SQLErrd: SQL internal error code.
//                cs.setIntX(5, getSqlErrd()[0]);
//                cs.setIntX(6, getSqlErrd()[1]);
//                cs.setIntX(7, getSqlErrd()[2]);
//                cs.setIntX(8, getSqlErrd()[3]);
//                cs.setIntX(9, getSqlErrd()[4]);
//                cs.setIntX(10, getSqlErrd()[5]);
//                // SQLWarn: SQL warning flags.
//                cs.setStringX(11, getSqlWarn());
//                // SQLState: standard SQL state.
//                cs.setStringX(12, sqlState);
//                // MessageFileName: Not used by our driver, so set to null.
//                cs.setStringX(13, null);
//                // Locale: language preference requested for the return error message.
//                cs.setStringX(14, Locale.getDefault().toString());
//                // server could return a locale different from what we requested
//                cs.registerOutParameterX(14, Types.VARCHAR);
//                // Message: error message returned from SQLCAMessage stored procedure.
//                cs.registerOutParameterX(15, Types.LONGVARCHAR);
//                // RCode: return code from SQLCAMessage stored procedure.
//                cs.registerOutParameterX(16, Types.INTEGER);
//                cs.executeX();
//
//                if (cs.getIntX(16) == 0) {
//                    // Return the message text.
//                    messageTextRetrievedContainsTokensOnly_ = false;
//                    String message = cs.getStringX(15);
//                    if (cachedMessages == null) {
//                        cachedMessages = new String[numberOfMessages()];
//                    }
//                    cachedMessages[messageNumber] = message;
//                    return message;
//                } else {
//                    // Stored procedure can't return a valid message text, so we return
//                    // unformated exception
//                    return getUnformattedMessage(messageNumber);
//                }
//            } finally {
//                if (cs != null) {
//                    try {
//                        cs.closeX();
//                    } catch (SqlException doNothing) {
//                    }
//                }
//            }
//        }
//    }

//    // May or may not get the formatted message depending upon datasource directives.  cannot throw exeption.
//    synchronized String getJDBCMessage(int messageNumber) {
//        // The transient connection_ member will only be null if the Sqlca has been deserialized
//        if (connection_ != null && connection_.retrieveMessageText_) {
//            try {
//                return getMessage(messageNumber);
//            } catch (SqlException e) {
//                // Invocation of stored procedure fails, so we return error message tokens directly.
//                exceptionThrownOnStoredProcInvocation_ = e;
//                chainDeferredExceptionsToAgentOrAsConnectionWarnings((SqlException) e);
//                return getUnformattedMessage(messageNumber);
//            }
//        } else {
//            return getUnformattedMessage(messageNumber);
//        }
//    }

//    /**
//     * Get the unformatted message text (in case we cannot ask the server).
//     *
//     * @param messageNumber which message number to get the text for
//     * @return string with details about the error
//     */
//    private String getUnformattedMessage(int messageNumber) {
//        int errorCode;
//        String sqlState;
//        String sqlErrmc;
//        if (messageNumber == 0) {
//            // if the first exception in the chain is requested, return all the
//            // information we have
//            errorCode = getErrorCode();
//            sqlState = getSqlState();
//            sqlErrmc = getSqlErrmc();
//        } else {
//            // otherwise, return information about the specified error only
//            errorCode = 0;
//            sqlState = sqlStates_[messageNumber];
//            sqlErrmc = sqlErrmcMessages_[messageNumber];
//        }
//        return "SQL error: ERRORCODE: " + errorCode + ", SQLSTATE: " +
//            sqlState + ", SQLERRMC: " + sqlErrmc;
//    }

//    private void chainDeferredExceptionsToAgentOrAsConnectionWarnings(SqlException e) {
//        SqlException current = e;
//        while (current != null) {
//            SqlException next = (SqlException) current.getNextException();
//            current = current.copyAsUnchainedSQLException(agent_.logWriter_);
//            if (current.getErrorCode() == -440) {
//                SqlWarning warningForStoredProcFailure = new SqlWarning(agent_.logWriter_,
//                    new ClientMessageId(SQLState.UNABLE_TO_OBTAIN_MESSAGE_TEXT_FROM_SERVER));
//                warningForStoredProcFailure.setNextException(current.getSQLException());
//                connection_.accumulate440WarningForMessageProcFailure(warningForStoredProcFailure);
//            } else if (current.getErrorCode() == -444) {
//                SqlWarning warningForStoredProcFailure = new SqlWarning(agent_.logWriter_,
//                    new ClientMessageId(SQLState.UNABLE_TO_OBTAIN_MESSAGE_TEXT_FROM_SERVER));
//                warningForStoredProcFailure.setNextException(current.getSQLException());
//                connection_.accumulate444WarningForMessageProcFailure(warningForStoredProcFailure);
//            } else {
//                agent_.accumulateDeferredException(current);
//            }
//            current = next;
//        }
//    }

//    /**
//     * Get a {@code java.sql.DataTruncation} warning based on the information
//     * in this SQLCA.
//     *
//     * @return a {@code java.sql.DataTruncation} instance
//     */
//    DataTruncation getDataTruncation() {
//        // The network server has serialized all the parameters needed by
//        // the constructor in the SQLERRMC field.
//        String[] tokens = getSqlErrmc().split(MessageUtils.SQLERRMC_TOKEN_DELIMITER);
//        return new DataTruncation(
//                Integer.parseInt(tokens[0]),                // index
//                Boolean.valueOf(tokens[1]).booleanValue(),  // parameter
//                Boolean.valueOf(tokens[2]).booleanValue(),  // read
//                Integer.parseInt(tokens[3]),                // dataSize
//                Integer.parseInt(tokens[4]));               // transferSize
//    }

    // ------------------- helper methods ----------------------------------------

    private void processSqlErrmcTokens(byte[] tokenBytes) {
        if (tokenBytes == null) {
            return;
        }

        // create 0-length String tokens array if tokenBytes is 0-length
        int length = tokenBytes.length;
        if (length == 0) {
            sqlStates_ = sqlErrmcMessages_ = new String[0];
            return;
        }

        // tokenize and convert tokenBytes
        String fullString = bytes2String(tokenBytes, 0, length);
        String[] tokens = fullString.split("\\u0014{3}");
        String[] states = new String[tokens.length];
        states[0] = getSqlState();
        for (int i = 1; i < tokens.length; i++) {
            // All but the first message are preceded by the SQL state
            // (five characters) and a colon. Extract the SQL state and
            // clean up the token. See
            // DRDAConnThread.buildTokenizedSqlerrmc() for more details.
            int colonpos = tokens[i].indexOf(":");
            states[i] = tokens[i].substring(0, colonpos);
            tokens[i] = tokens[i].substring(colonpos + 1);
        }
        sqlStates_ = states;
        sqlErrmcMessages_ = tokens;
    }

    private String bytes2String(byte[] bytes) {
        if (bytes == null)
            return null;
        return bytes2String(bytes, 0, bytes.length);
    }

    protected String bytes2String(byte[] bytes, int offset, int length) {
        // Network server uses utf8 encoding
        return new String(bytes, offset, length, CCSIDConstants.UTF8);
    }

    public long getUpdateCount() {
        if (sqlErrd_ == null) {
            return 0L;
        }
        long    result = getSqlErrd()[ LOW_ORDER_UPDATE_COUNT ];
        result &= 0xFFFFFFFFL;
        result |= ((long) getSqlErrd()[ HIGH_ORDER_UPDATE_COUNT ] << 32);
        return result;
    }

    public long getRowCount() {
        return ((long) getSqlErrd()[ HIGH_ORDER_ROW_COUNT ] << 32) +
                getSqlErrd()[ LOW_ORDER_ROW_COUNT ];
    }

    public void setContainsSqlcax(boolean containsSqlcax) {
        containsSqlcax_ = containsSqlcax;
    }

    public boolean containsSqlcax() {
        return containsSqlcax_;
    }

    public void resetRowsetSqlca(int sqlCode, String sqlState) {
        sqlCode_ = sqlCode;
        sqlState_ = sqlState;
        sqlErrpBytes_ = null;
    }

    public void setRowsetRowCount(long rowCount) {
        rowsetRowCount_ = rowCount;
    }

    public long getRowsetRowCount() {
        return rowsetRowCount_;
    }

    @Override
    public String toString() {
        return super.toString() +
                "  sqlCode=" + sqlCode_ +
                "  sqlState=" + sqlState_ +
                "  sqlErrd=" + Arrays.toString(sqlErrd_) +
                "  sqlErrmc="  + getSqlErrmc() +
                "  sqlErrp=" + bytes2String(sqlErrpBytes_) +
                "  sqlStates=" + Arrays.deepToString(sqlStates_) +
                "  sqlWarn=" + bytes2String(sqlWarnBytes_);
    }
}
