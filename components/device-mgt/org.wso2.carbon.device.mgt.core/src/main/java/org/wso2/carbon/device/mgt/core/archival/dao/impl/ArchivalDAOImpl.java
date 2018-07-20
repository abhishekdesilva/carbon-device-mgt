/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.core.archival.dao.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.device.mgt.core.archival.dao.*;

import java.sql.*;
import java.util.*;

public class ArchivalDAOImpl implements ArchivalDAO {

    private static final Log log = LogFactory.getLog(ArchivalDAOImpl.class);

    private int retentionPeriod;
    private int batchSize = ArchivalDAO.DEFAULT_BATCH_SIZE;
    private Timestamp currentTimestamp;


    public ArchivalDAOImpl(int retentionPeriod) {
        this.retentionPeriod = retentionPeriod;
    }

    public ArchivalDAOImpl(int retentionPeriod, int batchSize) {
        this.retentionPeriod = retentionPeriod;
        this.batchSize = batchSize;
        this.currentTimestamp = new Timestamp(new java.util.Date().getTime());
        if (log.isDebugEnabled()) {
            log.debug("Using batch size of " + this.batchSize + " with retention period " + this.retentionPeriod);
        }
    }

    @Override
    public List<Integer> getAllOperations() throws ArchivalDAOException {
        List<Integer> operationIds = new ArrayList<>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT DISTINCT OPERATION_ID FROM DM_ENROLMENT_OP_MAPPING " +
                    "WHERE CREATED_TIMESTAMP < DATE_SUB(NOW(), INTERVAL " + this.retentionPeriod + " DAY)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);
            if (log.isDebugEnabled()) {
                log.debug("Selected Operation Ids from Enrolment OP Mapping");
            }
            while (rs.next()) {
                operationIds.add(rs.getInt("OPERATION_ID"));
            }
        } catch (SQLException e) {
            String msg = "An error occurred while getting a list operation Ids to archive";
            log.error(msg,e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
        }
        if (log.isDebugEnabled()) {
            log.debug(operationIds.size() + " operations found for the archival");
            log.debug(operationIds.size() + "[" + operationIds.get(0) + "," + operationIds.get(batchSize - 1) + "]");
        }
        return operationIds;
    }

    @Override
    public List<Integer> getPendingAndInProgressOperations() throws ArchivalDAOException {
        List<Integer> operationIds = new ArrayList<>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT DISTINCT OPERATION_ID " +
                    " FROM DM_ENROLMENT_OP_MAPPING WHERE STATUS='PENDING' OR STATUS='IN_PROGRESS' " +
                    " AND CREATED_TIMESTAMP < DATE_SUB(NOW(), INTERVAL " + this.retentionPeriod + " DAY)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);
            if (log.isDebugEnabled()) {
                log.debug("Selected Pending or In Progress Operation IDs");
            }
            while (rs.next()) {
                operationIds.add(rs.getInt("OPERATION_ID"));
            }
        } catch (SQLException e) {
            String msg = "An error occurred while getting a list pending or in progress operation Ids to archive";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
        }
        if (log.isDebugEnabled()) {
            log.debug(operationIds.size() + " operations found for the archival");
            log.debug(operationIds.size() + "[" + operationIds.get(0) + "," + operationIds.get(batchSize - 1) + "]");
        }
        return operationIds;
    }

    @Override
    public void copyOperationIDsForArchival(List<Integer> operationIds) throws ArchivalDAOException {
        PreparedStatement stmt = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "INSERT INTO DM_ARCHIVED_OPERATIONS(ID,CREATED_TIMESTAMP) VALUES (?,NOW())";
            stmt = conn.prepareStatement(sql);

            int count = 0;
            for (int i = 0; i < operationIds.size(); i++) {
                stmt.setInt(1, operationIds.get(i));
                stmt.addBatch();

                if (++count % this.batchSize == 0) {
                    stmt.executeBatch();
                }
            }
            stmt.executeBatch();
            if (log.isDebugEnabled()) {
                log.debug(count + " Records copied to the temporary table.");
            }
        } catch (SQLException e) {
            String msg = "Error while copying operation Ids for archival";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt);
        }
    }

    @Override
    public void moveOperationResponses() throws ArchivalDAOException {
        Statement stmt = null;
        PreparedStatement stmt2 = null;
        Statement stmt3 = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT * FROM DM_DEVICE_OPERATION_RESPONSE WHERE OPERATION_ID IN " +
                    "(SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);

            if (log.isDebugEnabled()) {
                log.debug("Selecting done for the Operation Response");
            }

            Connection conn2 = ArchivalDestinationDAOFactory.getConnection();
            sql = "INSERT INTO DM_DEVICE_OPERATION_RESPONSE_ARCH VALUES(?, ?, ?, ?, ?,?)";
            stmt2 = conn2.prepareStatement(sql);

            int count = 0;
            while (rs.next()) {
                stmt2.setInt(1, rs.getInt("ID"));
                stmt2.setInt(2, rs.getInt("ENROLMENT_ID"));
                stmt2.setInt(3, rs.getInt("OPERATION_ID"));
                stmt2.setBytes(4, rs.getBytes("OPERATION_RESPONSE"));
                stmt2.setTimestamp(5, rs.getTimestamp("RECEIVED_TIMESTAMP"));
                stmt2.setTimestamp(6, this.currentTimestamp);
                stmt2.addBatch();

                if (++count % batchSize == 0) {
                    stmt2.executeBatch();
                    if (log.isDebugEnabled()) {
                        log.debug("Executing Operation Responses batch " + count);
                    }
                }
            }
            stmt2.executeBatch();
            if (log.isDebugEnabled()) {
                log.debug(count + " [OPERATION_RESPONSES] Records copied to the archival table. Starting deletion");
            }
            //try the deletion now
            sql = "DELETE FROM DM_DEVICE_OPERATION_RESPONSE WHERE OPERATION_ID IN (" +
                    " SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt3 = conn.createStatement();
            int affected = stmt3.executeUpdate(sql);
            if (log.isDebugEnabled()) {
                log.debug(affected + " Rows deleted");
            }
        } catch (SQLException e) {
            String msg = "Error occurred while archiving the operation responses";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
            ArchivalDAOUtil.cleanupResources(stmt2);
            ArchivalDAOUtil.cleanupResources(stmt3);
        }
    }

    @Override
    public void moveNotifications() throws ArchivalDAOException {
        Statement stmt = null;
        PreparedStatement stmt2 = null;
        Statement stmt3 = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT * FROM DM_NOTIFICATION WHERE OPERATION_ID IN (SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);

            if (log.isDebugEnabled()) {
                log.debug("Selecting done for the Notification");
            }

//            ArchivalDestinationDAOFactory.beginTransaction();
            Connection conn2 = ArchivalDestinationDAOFactory.getConnection();

            sql = "INSERT INTO DM_NOTIFICATION_ARCH VALUES(?, ?, ?, ?, ?, ?, ?)";
            stmt2 = conn2.prepareStatement(sql);

            int count = 0;
            while (rs.next()) {
                stmt2.setInt(1, rs.getInt("NOTIFICATION_ID"));
                stmt2.setInt(2, rs.getInt("DEVICE_ID"));
                stmt2.setInt(3, rs.getInt("OPERATION_ID"));
                stmt2.setInt(4, rs.getInt("TENANT_ID"));
                stmt2.setString(5, rs.getString("STATUS"));
                stmt2.setString(6, rs.getString("DESCRIPTION"));
                stmt2.setTimestamp(7, this.currentTimestamp);
                stmt2.addBatch();

                if (++count % batchSize == 0) {
                    stmt2.executeBatch();
                    if (log.isDebugEnabled()) {
                        log.debug("Executing Notifications batch " + count);
                    }
                }
            }
            stmt2.executeBatch();
//            ArchivalDestinationDAOFactory.commitTransaction();
            if (log.isDebugEnabled()) {
                log.debug(count + " [NOTIFICATIONS] Records copied to the archival table. Starting deletion");
            }
            sql = "DELETE FROM DM_NOTIFICATION" +
                    "  WHERE OPERATION_ID IN (SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt3 = conn.createStatement();
            int affected = stmt3.executeUpdate(sql);
            if (log.isDebugEnabled()) {
                log.debug(affected + " Rows deleted");
            }
        } catch (SQLException e) {
            String msg = "Error occurred while archiving the notifications";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
            ArchivalDAOUtil.cleanupResources(stmt2);
            ArchivalDAOUtil.cleanupResources(stmt3);
        }
    }

    @Override
    public void moveCommandOperations() throws ArchivalDAOException {
        Statement stmt = null;
        PreparedStatement stmt2 = null;
        Statement stmt3 = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT * FROM DM_COMMAND_OPERATION WHERE OPERATION_ID IN " +
                    "(SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);

            if (log.isDebugEnabled()) {
                log.debug("Selecting done for the Command Operation");
            }

            Connection conn2 = ArchivalDestinationDAOFactory.getConnection();

            sql = "INSERT INTO DM_COMMAND_OPERATION_ARCH VALUES(?,?,?)";
            stmt2 = conn2.prepareStatement(sql);

            int count = 0;
            while (rs.next()) {
                stmt2.setInt(1, rs.getInt("OPERATION_ID"));
                stmt2.setInt(2, rs.getInt("ENABLED"));
                stmt2.setTimestamp(3, this.currentTimestamp);
                stmt2.addBatch();

                if (++count % batchSize == 0) {
                    stmt2.executeBatch();
                    if (log.isDebugEnabled()) {
                        log.debug("Executing Command Operations batch " + count);
                    }
                }
            }
            stmt2.executeBatch();
            if (log.isDebugEnabled()) {
                log.debug(count + " [COMMAND_OPERATION] Records copied to the archival table. Starting deletion");
            }
            sql = "DELETE FROM DM_COMMAND_OPERATION" +
                    "  WHERE OPERATION_ID IN (SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt3 = conn.createStatement();
            int affected = stmt3.executeUpdate(sql);
            if (log.isDebugEnabled()) {
                log.debug(affected + " Rows deleted");
            }
        } catch (SQLException e) {
            String msg = "Error occurred while archiving the command operation";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
            ArchivalDAOUtil.cleanupResources(stmt2);
            ArchivalDAOUtil.cleanupResources(stmt3);
        }
    }

    @Override
    public void moveProfileOperations() throws ArchivalDAOException {
        Statement stmt = null;
        PreparedStatement stmt2 = null;
        Statement stmt3 = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT * FROM DM_PROFILE_OPERATION WHERE OPERATION_ID IN " +
                    "(SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);

            if (log.isDebugEnabled()) {
                log.debug("Selecting done for the Profile Operation");
            }

            Connection conn2 = ArchivalDestinationDAOFactory.getConnection();

            sql = "INSERT INTO DM_PROFILE_OPERATION_ARCH VALUES(?, ?, ?, ?)";
            stmt2 = conn2.prepareStatement(sql);

            int count = 0;
            while (rs.next()) {
                stmt2.setInt(1, rs.getInt("OPERATION_ID"));
                stmt2.setInt(2, rs.getInt("ENABLED"));
                stmt2.setBytes(3, rs.getBytes("OPERATION_DETAILS"));
                stmt2.setTimestamp(4,this.currentTimestamp );
                stmt2.addBatch();

                if (++count % batchSize == 0) {
                    stmt2.executeBatch();
                    if (log.isDebugEnabled()) {
                        log.debug("Executing Profile Operations batch " + count);
                    }
                }
            }
            stmt2.executeBatch();
            if (log.isDebugEnabled()) {
                log.debug(count + " [PROFILE_OPERATION] Records copied to the archival table. Starting deletion");
            }
            sql = "DELETE FROM DM_PROFILE_OPERATION" +
                    "  WHERE OPERATION_ID IN (SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt3 = conn.createStatement();
            int affected = stmt3.executeUpdate(sql);
            if (log.isDebugEnabled()) {
                log.debug(affected + " Rows deleted");
            }
        } catch (SQLException e) {
            String msg = "Error occurred while archiving the profile operation";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
            ArchivalDAOUtil.cleanupResources(stmt2);
            ArchivalDAOUtil.cleanupResources(stmt3);
        }
    }

    @Override
    public void moveEnrolmentMappings() throws ArchivalDAOException {
        Statement stmt = null;
        PreparedStatement stmt2 = null;
        Statement stmt3 = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT * FROM DM_ENROLMENT_OP_MAPPING WHERE OPERATION_ID IN " +
                    "(SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);

            if (log.isDebugEnabled()) {
                log.debug("Selecting done for the Enrolment OP Mapping");
            }

            Connection conn2 = ArchivalDestinationDAOFactory.getConnection();

            sql = "INSERT INTO DM_ENROLMENT_OP_MAPPING_ARCH VALUES(?, ?, ?, ?, ?, ?, ?)";
            stmt2 = conn2.prepareStatement(sql);

            int count = 0;
            while (rs.next()) {
                stmt2.setInt(1, rs.getInt("ID"));
                stmt2.setInt(2, rs.getInt("ENROLMENT_ID"));
                stmt2.setInt(3, rs.getInt("OPERATION_ID"));
                stmt2.setString(4, rs.getString("STATUS"));
                stmt2.setInt(5, rs.getInt("CREATED_TIMESTAMP"));
                stmt2.setInt(6, rs.getInt("UPDATED_TIMESTAMP"));
                stmt2.setTimestamp(7, this.currentTimestamp);
                stmt2.addBatch();

                if (++count % batchSize == 0) {
                    stmt2.executeBatch();
                    if (log.isDebugEnabled()) {
                        log.debug("Executing Enrolment Mappings batch " + count);
                    }
                }
            }
            stmt2.executeBatch();
            if (log.isDebugEnabled()) {
                log.debug(count + " [ENROLMENT_OP_MAPPING] Records copied to the archival table. Starting deletion");
            }
            sql = "DELETE FROM DM_ENROLMENT_OP_MAPPING WHERE OPERATION_ID IN (" +
                    "SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt3 = conn.createStatement();
            int affected = stmt3.executeUpdate(sql);
            if (log.isDebugEnabled()) {
                log.debug(affected + " Rows deleted");
            }
        } catch (SQLException e) {
            String msg = "Error occurred while archiving the enrolment op mappings";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
            ArchivalDAOUtil.cleanupResources(stmt2);
            ArchivalDAOUtil.cleanupResources(stmt3);
        }
    }

    @Override
    public void moveOperations() throws ArchivalDAOException {
        Statement stmt = null;
        PreparedStatement stmt2 = null;
        Statement stmt3 = null;
        ResultSet rs = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            String sql = "SELECT * FROM DM_OPERATION WHERE ID IN (SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt = this.createMemoryEfficientStatement(conn);
            rs = stmt.executeQuery(sql);

            if (log.isDebugEnabled()) {
                log.debug("Selecting done for the Operation");
            }

            Connection conn2 = ArchivalDestinationDAOFactory.getConnection();
            sql = "INSERT INTO DM_OPERATION_ARCH VALUES(?, ?, ?, ?, ?, ?)";
            stmt2 = conn2.prepareStatement(sql);

            int count = 0;
            while (rs.next()) {
                stmt2.setInt(1, rs.getInt("ID"));
                stmt2.setString(2, rs.getString("TYPE"));
                stmt2.setTimestamp(3, rs.getTimestamp("CREATED_TIMESTAMP"));
                stmt2.setTimestamp(4, rs.getTimestamp("RECEIVED_TIMESTAMP"));
                stmt2.setString(5, rs.getString("OPERATION_CODE"));
                stmt2.setTimestamp(6, this.currentTimestamp);
                stmt2.addBatch();

                if (++count % batchSize == 0) {
                    stmt2.executeBatch();
                    if (log.isDebugEnabled()) {
                        log.debug("Final Execution of Operations batch " + count);
                    }
                }
            }
            stmt2.executeBatch();
            if (log.isDebugEnabled()) {
                log.debug(count + " [OPERATIONS] Records copied to the archival table. Starting deletion");
            }
            sql = "DELETE FROM DM_OPERATION WHERE ID IN (" +
                    "SELECT ID FROM DM_ARCHIVED_OPERATIONS)";
            stmt3 = conn.createStatement();
            int affected = stmt3.executeUpdate(sql);
            if (log.isDebugEnabled()) {
                log.debug(affected + " Rows deleted");
            }
        } catch (SQLException e) {
            String msg = "Error occurred while archiving the operations";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt, rs);
            ArchivalDAOUtil.cleanupResources(stmt2);
            ArchivalDAOUtil.cleanupResources(stmt3);
        }
    }

    @Override
    public void truncateOperationIDsForArchival() throws ArchivalDAOException {
        PreparedStatement stmt = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
            conn.setAutoCommit(false);
            String sql = "TRUNCATE DM_ARCHIVED_OPERATIONS";
            stmt = conn.prepareStatement(sql);
//            stmt.addBatch();
//            stmt.executeBatch();
            stmt.executeUpdate();

            conn.commit();
        } catch (SQLException e) {
            String msg = "Error occurred while truncating operation Ids";
            log.error(msg, e);
            throw new ArchivalDAOException(msg, e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt);
        }
    }

    private Statement createMemoryEfficientStatement(Connection conn) throws ArchivalDAOException, SQLException {
        Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        stmt.setFetchSize(Integer.MIN_VALUE);
        return stmt;
    }

    private String buildWhereClause (String[] statuses) {
        StringBuilder whereClause = new StringBuilder("WHERE ");
        for (int i = 0; i < statuses.length; i++) {
            whereClause.append("STATUS ='");
            whereClause.append(statuses[i]);
            whereClause.append("' ");
            if(i != (statuses.length -1))
                whereClause.append(" OR ");
        }
        return whereClause.toString();
    }

    private void copyOperationIDsForArchival() throws ArchivalDAOException {
        PreparedStatement stmt = null;
        Statement createStmt = null;
        try {
            Connection conn = ArchivalSourceDAOFactory.getConnection();
//            conn.setAutoCommit(false);
//            String sql = "INSERT INTO DM_ARCHIVED_OPERATIONS(ID,CREATED_TIMESTAMP)" +
//                    " SELECT DISTINCT op.ID as OPERATION_ID, NOW()" +
//                    " FROM DM_ENROLMENT_OP_MAPPING AS opm" +
//                    " LEFT JOIN DM_OPERATION AS op ON opm.OPERATION_ID = op.ID" +
//                    " WHERE opm.STATUS='ERROR' OR opm.STATUS='COMPLETED'" +
//                    " AND op.RECEIVED_TIMESTAMP < DATE_SUB(NOW(), INTERVAL ? DAY);";
//            stmt = conn.prepareStatement(sql);
//            stmt.setInt(1, this.retentionPeriod);
//            stmt.addBatch();
//            stmt.executeBatch();
//            conn.commit();

            //Create the temporary table first
//            String sql = "CREATE TEMPORARY TABLE DM_ARCHIVED_OPERATIONS (ID INTEGER NOT NULL," +
//                    "    CREATED_TIMESTAMP TIMESTAMP NOT NULL, PRIMARY KEY (ID))" ;
//            createStmt = conn.createStatement();
//            createStmt.execute(sql);
//            if(log.isDebugEnabled()) {
//                log.debug("Temporary table DM_ARCHIVED_OPERATIONS has been created ");
//            }
            //Copy eligible operations into DM_ARCHIVED_OPERATIONS
            String sql = "INSERT INTO DM_ARCHIVED_OPERATIONS(ID,CREATED_TIMESTAMP)" +
                    " SELECT DISTINCT OPERATION_ID, NOW()" +
                    " FROM DM_ENROLMENT_OP_MAPPING" +
                    " WHERE STATUS='ERROR' OR STATUS='COMPLETED' OR STATUS='REPEATED'" +
                    " AND CREATED_TIMESTAMP < DATE_SUB(NOW(), INTERVAL ? DAY)";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, this.retentionPeriod);
            int affected = stmt.executeUpdate();
            log.info(affected + " Eligible operations found for archival");
        } catch (SQLException e) {
            throw new ArchivalDAOException("Error occurred while copying operation Ids for archival", e);
        } finally {
            ArchivalDAOUtil.cleanupResources(stmt);
            ArchivalDAOUtil.cleanupResources(createStmt);
        }
    }
}
