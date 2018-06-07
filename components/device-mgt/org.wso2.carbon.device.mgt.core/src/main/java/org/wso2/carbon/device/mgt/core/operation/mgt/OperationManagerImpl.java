/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.core.operation.mgt;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.device.mgt.common.*;
import org.wso2.carbon.device.mgt.common.authorization.DeviceAccessAuthorizationException;
import org.wso2.carbon.device.mgt.common.group.mgt.DeviceGroupConstants;
import org.wso2.carbon.device.mgt.common.operation.mgt.*;
import org.wso2.carbon.device.mgt.common.push.notification.NotificationContext;
import org.wso2.carbon.device.mgt.common.push.notification.NotificationStrategy;
import org.wso2.carbon.device.mgt.common.push.notification.PushNotificationExecutionFailedException;
import org.wso2.carbon.device.mgt.core.DeviceManagementConstants;
import org.wso2.carbon.device.mgt.core.config.DeviceConfigurationManager;
import org.wso2.carbon.device.mgt.core.config.task.TaskConfiguration;
import org.wso2.carbon.device.mgt.core.dao.DeviceDAO;
import org.wso2.carbon.device.mgt.core.dao.DeviceManagementDAOException;
import org.wso2.carbon.device.mgt.core.dao.DeviceManagementDAOFactory;
import org.wso2.carbon.device.mgt.core.internal.DeviceManagementDataHolder;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationDAO;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationManagementDAOException;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationManagementDAOFactory;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationMappingDAO;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.util.OperationDAOUtil;
import org.wso2.carbon.device.mgt.core.operation.mgt.util.DeviceIDHolder;
import org.wso2.carbon.device.mgt.core.operation.mgt.util.OperationCreateTimeComparator;
import org.wso2.carbon.device.mgt.core.task.DeviceTaskManager;
import org.wso2.carbon.device.mgt.core.task.impl.DeviceTaskManagerImpl;
import org.wso2.carbon.device.mgt.core.util.DeviceManagerUtil;

import java.sql.SQLException;
import java.util.*;

/**
 * This class implements all the functionality exposed as part of the OperationManager. Any transaction initiated
 * upon persisting information related to operation state, etc has to be managed, demarcated and terminated via the
 * methods available in OperationManagementDAOFactory.
 */
public class OperationManagerImpl implements OperationManager {

    private static final Log log = LogFactory.getLog(OperationManagerImpl.class);

    private OperationDAO commandOperationDAO;
    private OperationDAO configOperationDAO;
    private OperationDAO profileOperationDAO;
    private OperationDAO policyOperationDAO;
    private OperationMappingDAO operationMappingDAO;
    private OperationDAO operationDAO;
    private DeviceDAO deviceDAO;
    private NotificationStrategy notificationStrategy;

    public OperationManagerImpl() {
        commandOperationDAO = OperationManagementDAOFactory.getCommandOperationDAO();
        configOperationDAO = OperationManagementDAOFactory.getConfigOperationDAO();
        profileOperationDAO = OperationManagementDAOFactory.getProfileOperationDAO();
        policyOperationDAO = OperationManagementDAOFactory.getPolicyOperationDAO();
        operationMappingDAO = OperationManagementDAOFactory.getOperationMappingDAO();
        operationDAO = OperationManagementDAOFactory.getOperationDAO();
        deviceDAO = DeviceManagementDAOFactory.getDeviceDAO();
    }

    public OperationManagerImpl(NotificationStrategy notificationStrategy) {
        this();
        this.notificationStrategy = notificationStrategy;
    }

    @Override
    public Activity addOperation(Operation operation,
                                 List<DeviceIdentifier> deviceIds)
            throws OperationManagementException, InvalidDeviceException {
        if (log.isDebugEnabled()) {
            log.debug("operation:[" + operation.toString() + "]");
            for (DeviceIdentifier deviceIdentifier : deviceIds) {
                log.debug("device identifier id:[" + deviceIdentifier.getId() + "] type:[" +
                          deviceIdentifier.getType() + "]");
            }
        }
        try {
            DeviceIDHolder deviceValidationResult = DeviceManagerUtil.validateDeviceIdentifiers(deviceIds);
            List<DeviceIdentifier> validDeviceIds = deviceValidationResult.getValidDeviceIDList();
            if (validDeviceIds.size() > 0) {
                DeviceIDHolder deviceAuthorizationResult = this.authorizeDevices(operation, validDeviceIds);
                List<DeviceIdentifier> authorizedDeviceList = deviceAuthorizationResult.getValidDeviceIDList();
                if (authorizedDeviceList.size() <= 0) {
                    log.info("User : " + getUser() + " is not authorized to perform operations on given device-list.");
                    Activity activity = new Activity();
                    //Send the operation statuses only for admin triggered operations
                    String deviceType = validDeviceIds.get(0).getType();
                    activity.setActivityStatus(this.getActivityStatus(deviceValidationResult, deviceAuthorizationResult,
                                                                      deviceType));
                    return activity;
                }

                OperationManagementDAOFactory.beginTransaction();
                org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation operationDto =
                        OperationDAOUtil.convertOperation(operation);
                boolean isScheduledOperation = this.isTaskScheduledOperation(operation);
                boolean isNotRepeated = false;
                int enrolmentId;
                Map<String,String> deviceIdActivityMap = new HashMap<>();
                if (operationDto.getControl() ==
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Control.NO_REPEAT) {
                    isNotRepeated = true;
                }

                //TODO have to create a sql to load device details from deviceDAO using single query.
                String operationCode = operationDto.getCode();
                for (DeviceIdentifier deviceId : authorizedDeviceList) {
                    Device device = getDevice(deviceId);
                    enrolmentId = device.getEnrolmentInfo().getId();
                    //Do not repeat the task operations
                    if (isScheduledOperation) {
                        String existingOperationId = operationDAO.hasExistingOperations(enrolmentId, operationCode);
                        if (!existingOperationId.equals("")) {
                            deviceIdActivityMap.put(deviceId.getId(), DeviceManagementConstants.OperationAttributes.ACTIVITY + existingOperationId);
                        }else{
                            int operationId = this.lookupOperationDAO(operation).addOperation(operationDto);
                            operationMappingDAO.addOperationMapping(operationId, enrolmentId);
                            deviceIdActivityMap.put(deviceId.getId(),DeviceManagementConstants.OperationAttributes.ACTIVITY + operationId);
                        }
                    } else if (isNotRepeated) {
                        int operationId = this.lookupOperationDAO(operation).addOperation(operationDto);
                        operationDAO.updateEnrollmentOperationsStatus(enrolmentId, operationCode,
                                                                      org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING,
                                                                      org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.REPEATED);
                        operationMappingDAO.addOperationMapping(operationId, enrolmentId);
                        deviceIdActivityMap.put(deviceId.getId(),DeviceManagementConstants.OperationAttributes.ACTIVITY + operationId);
                    } else {
                        int operationId = this.lookupOperationDAO(operation).addOperation(operationDto);
                        operationMappingDAO.addOperationMapping(operationId, enrolmentId);
                        deviceIdActivityMap.put(deviceId.getId(),DeviceManagementConstants.OperationAttributes.ACTIVITY + operationId);
                    }
                    if (notificationStrategy != null) {
                        try {
                            notificationStrategy.execute(new NotificationContext(deviceId, operation));
                        } catch (PushNotificationExecutionFailedException e) {
                            log.error("Error occurred while sending push notifications to " +
                                      deviceId.getType() + " device carrying id '" +
                                      deviceId + "'", e);
                        }
                    }
                }

                OperationManagementDAOFactory.commitTransaction();
                Activity activity = new Activity();
                activity.setActivityId(deviceIdActivityMap.get(deviceIds.get(0).getId()));
                activity.setCode(operationCode);
                activity.setCreatedTimeStamp(new Date().toString());
                activity.setType(Activity.Type.valueOf(operationDto.getType().toString()));
                //For now set the operation statuses only for admin triggered operations
                if (!isScheduledOperation) {
                    //Get the device-type from 1st valid DeviceIdentifier. We know the 1st element is definitely there.
                    String deviceType = validDeviceIds.get(0).getType();
                    activity.setActivityStatus(this.getActivityStatus(deviceValidationResult, deviceAuthorizationResult, deviceType));
                }
                return activity;
            } else {
                throw new InvalidDeviceException("Invalid device Identifiers found.");
            }

        } catch (OperationManagementDAOException e) {
            OperationManagementDAOFactory.rollbackTransaction();
            throw new OperationManagementException("Error occurred while adding operation", e);
        } catch (TransactionManagementException e) {
            throw new OperationManagementException("Error occurred while initiating the transaction", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    private List<ActivityStatus> getActivityStatus(DeviceIDHolder deviceIdValidationResult, DeviceIDHolder deviceAuthResult,
                                                   String deviceType) {
        List<ActivityStatus> activityStatuses = new ArrayList<>();
        ActivityStatus activityStatus;
        //Add the invalid DeviceIds
        for (String id : deviceIdValidationResult.getErrorDeviceIdList()) {
            activityStatus = new ActivityStatus();
            activityStatus.setDeviceIdentifier(new DeviceIdentifier(id,deviceType));
            activityStatus.setStatus(ActivityStatus.Status.INVALID);
            activityStatuses.add(activityStatus);
        }

        //Add the unauthorized DeviceIds
        for (String id : deviceAuthResult.getErrorDeviceIdList()) {
            activityStatus = new ActivityStatus();
            activityStatus.setDeviceIdentifier(new DeviceIdentifier(id, deviceType));
            activityStatus.setStatus(ActivityStatus.Status.UNAUTHORIZED);
            activityStatuses.add(activityStatus);
        }

        //Add the authorized DeviceIds
        for (DeviceIdentifier id : deviceAuthResult.getValidDeviceIDList()) {
            activityStatus = new ActivityStatus();
            activityStatus.setDeviceIdentifier(id);
            activityStatus.setStatus(ActivityStatus.Status.PENDING);
            activityStatuses.add(activityStatus);
        }
        return activityStatuses;
    }

    private DeviceIDHolder authorizeDevices(
            Operation operation, List<DeviceIdentifier> deviceIds) throws OperationManagementException {
        List<DeviceIdentifier> authorizedDeviceList;
        List<String> unAuthorizedDeviceList = new ArrayList<>();
        DeviceIDHolder deviceIDHolder = new DeviceIDHolder();
        try {
            if (operation != null && isAuthenticationSkippedOperation(operation)) {
                authorizedDeviceList = deviceIds;
            } else {
                boolean isAuthorized;
                authorizedDeviceList = new ArrayList<>();
                for (DeviceIdentifier devId : deviceIds) {
                    isAuthorized = DeviceManagementDataHolder.getInstance().getDeviceAccessAuthorizationService().
                            isUserAuthorized(devId);
                    if (isAuthorized) {
                        authorizedDeviceList.add(devId);
                    } else {
                        unAuthorizedDeviceList.add(devId.getId());
                    }
                }
            }
        } catch (DeviceAccessAuthorizationException e) {
            throw new OperationManagementException("Error occurred while authorizing access to the devices for user :" +
                                                   this.getUser(), e);
        }
        deviceIDHolder.setValidDeviceIDList(authorizedDeviceList);
        deviceIDHolder.setErrorDeviceIdList(unAuthorizedDeviceList);
        return deviceIDHolder;
    }

    private Device getDevice(DeviceIdentifier deviceId) throws OperationManagementException {
        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        try {
            DeviceManagementDAOFactory.openConnection();
            return deviceDAO.getDevice(deviceId, tenantId);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection the data " +
                                                   "source", e);
        } catch (DeviceManagementDAOException e) {
            OperationManagementDAOFactory.rollbackTransaction();
            throw new OperationManagementException(
                    "Error occurred while retrieving device info", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<? extends Operation> getOperations(DeviceIdentifier deviceId) throws OperationManagementException {
        List<Operation> operations = null;

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                                                   deviceId.getType() + "' device, which carries the identifier '" +
                                                   deviceId.getId() + "'");
        }

        int enrolmentId = this.getEnrolmentByStatus(deviceId, EnrolmentInfo.Status.ACTIVE);
        if (enrolmentId < 0) {
            return null;
        }

        try {
            OperationManagementDAOFactory.openConnection();
            List<? extends org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> operationList =
                    operationDAO.getOperationsForDevice(enrolmentId);

            operations = new ArrayList<>();
            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : operationList) {
                Operation operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                                                   "operations assigned for '" + deviceId.getType() +
                                                   "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operations;
    }

    @Override
    public PaginationResult getOperations(DeviceIdentifier deviceId, PaginationRequest request)
            throws OperationManagementException {
        PaginationResult paginationResult = null;
        List<Operation> operations = new ArrayList<>();

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                                                   deviceId.getType() + "' device, which carries the identifier '" +
                                                   deviceId.getId() + "'");
        }

        int enrolmentId = this.getEnrolmentByStatus(deviceId, EnrolmentInfo.Status.ACTIVE);
        if (enrolmentId < 0) {
            throw new OperationManagementException("Device not found for given device " +
                                                   "Identifier:" + deviceId.getId() + " and given type" +
                                                   deviceId.getType());
        }

        try {
            OperationManagementDAOFactory.openConnection();
            List<? extends org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> operationList =
                    operationDAO.getOperationsForDevice(enrolmentId, request);
            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : operationList) {
                Operation operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }
            paginationResult = new PaginationResult();
            int count = operationDAO.getOperationCountForDevice(enrolmentId);
            paginationResult.setData(operations);
            paginationResult.setRecordsTotal(count);
            paginationResult.setRecordsFiltered(count);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                                                   "operations assigned for '" + deviceId.getType() +
                                                   "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }

        return paginationResult;
    }

    @Override
    public List<? extends Operation> getPendingOperations(DeviceIdentifier deviceId) throws
                                                                                     OperationManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Device identifier id:[" + deviceId.getId() + "] type:[" + deviceId.getType() + "]");
        }
        List<Operation> operations = new ArrayList<>();
        List<org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> dtoOperationList = new ArrayList<>();

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                                                   deviceId.getType() + "' device, which carries the identifier '" +
                                                   deviceId.getId() + "'");
        }

        int enrolmentId = this.getEnrolmentByStatus(deviceId, EnrolmentInfo.Status.ACTIVE);
        if (enrolmentId < 0) {
            throw new OperationManagementException("Device not found for the given device Identifier:" +
                                                   deviceId.getId() + " and given type:" +
                                                   deviceId.getType());
        }

        try {
            OperationManagementDAOFactory.openConnection();
            dtoOperationList.addAll(commandOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(configOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(profileOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(policyOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            Operation operation;
            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : dtoOperationList) {
                operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }
            Collections.sort(operations, new OperationCreateTimeComparator());
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                                                   "pending operations assigned for '" + deviceId.getType() +
                                                   "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operations;
    }

    @Override
    public Operation getNextPendingOperation(DeviceIdentifier deviceId) throws OperationManagementException {
        if (log.isDebugEnabled()) {
            log.debug("device identifier id:[" + deviceId.getId() + "] type:[" + deviceId.getType() + "]");
        }
        Operation operation = null;

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                                                   deviceId.getType() + "' device, which carries the identifier '" +
                                                   deviceId.getId() + "'");
        }

        int enrolmentId = this.getEnrolmentByStatus(deviceId, EnrolmentInfo.Status.ACTIVE);
        if (enrolmentId < 0) {
            throw new OperationManagementException("Device not found for given device " +
                                                   "Identifier:" + deviceId.getId() + " and given type" +
                                                   deviceId.getType());
        }

        try {
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation = operationDAO.
                                                                                                           getNextOperation(
                                                                                                                   enrolmentId);
            if (dtoOperation != null) {
                if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.COMMAND.
                                                                                                    equals(dtoOperation
                                                                                                                   .getType())) {
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation commandOperation;
                    commandOperation =
                            (org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) commandOperationDAO.
                                                                                                                            getOperation(
                                                                                                                                    dtoOperation
                                                                                                                                            .getId());
                    dtoOperation.setEnabled(commandOperation.isEnabled());
                } else if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.CONFIG.
                                                                                                          equals(dtoOperation
                                                                                                                         .getType())) {
                    dtoOperation = configOperationDAO.getOperation(dtoOperation.getId());
                } else if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.PROFILE.
                                                                                                           equals(dtoOperation
                                                                                                                          .getType())) {
                    dtoOperation = profileOperationDAO.getOperation(dtoOperation.getId());
                } else if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.POLICY.
                                                                                                          equals(dtoOperation
                                                                                                                         .getType())) {
                    dtoOperation = policyOperationDAO.getOperation(dtoOperation.getId());
                }
                operation = OperationDAOUtil.convertOperation(dtoOperation);
            }
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving next pending operation", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operation;
    }

    @Override
    public void updateOperation(DeviceIdentifier deviceId, Operation operation) throws OperationManagementException {
        int operationId = operation.getId();
        if (log.isDebugEnabled()) {
            log.debug("operation Id:" + operationId + " status:" + operation.getStatus());
        }

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                                                   deviceId.getType() + "' device, which carries the identifier '" +
                                                   deviceId.getId() + "'");
        }

        int enrolmentId = this.getEnrolmentByStatus(deviceId, EnrolmentInfo.Status.ACTIVE);

        try {
            OperationManagementDAOFactory.beginTransaction();
            boolean isUpdated = false;
            if (operation.getStatus() != null) {
                isUpdated = operationDAO.updateOperationStatus(enrolmentId, operationId,
                                                               org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status
                                                                       .
                                                                               valueOf(operation.getStatus()
                                                                                                .toString()));
            }
            if (isUpdated && operation.getOperationResponse() != null) {
                operationDAO.addOperationResponse(enrolmentId, operationId, operation.getOperationResponse());
            }
            OperationManagementDAOFactory.commitTransaction();
        } catch (OperationManagementDAOException e) {
            OperationManagementDAOFactory.rollbackTransaction();
            throw new OperationManagementException(
                    "Error occurred while updating the operation: " + operationId + " status:" +
                    operation.getStatus(), e);
        } catch (TransactionManagementException e) {
            throw new OperationManagementException("Error occurred while initiating a transaction", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public void deleteOperation(int operationId) throws OperationManagementException {
        try {
            OperationManagementDAOFactory.beginTransaction();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation operation =
                    operationDAO.getOperation(operationId);
            if (operation == null) {
                throw new OperationManagementException("Operation not found for operation id : " + operationId);
            }
            lookupOperationDAO(operation).deleteOperation(operationId);
            OperationManagementDAOFactory.commitTransaction();
        } catch (OperationManagementDAOException e) {
            OperationManagementDAOFactory.rollbackTransaction();
            throw new OperationManagementException("Error occurred while deleting the operation: " + operationId, e);
        } catch (TransactionManagementException e) {
            throw new OperationManagementException("Error occurred while initiating a transaction", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public Operation getOperationByDeviceAndOperationId(DeviceIdentifier deviceId, int operationId)
            throws OperationManagementException {
        Operation operation = null;
        if (log.isDebugEnabled()) {
            log.debug("Operation Id: " + operationId + " Device Type: " + deviceId.getType() + " Device Identifier: " +
                      deviceId.getId());
        }

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                                                   deviceId.getType() + "' device, which carries the identifier '" +
                                                   deviceId.getId() + "'");
        }

        int enrolmentId = this.getEnrolmentByStatus(deviceId, EnrolmentInfo.Status.ACTIVE);
        if (enrolmentId < 0) {
            throw new OperationManagementException("Device not found for given device identifier: " +
                                                   deviceId.getId() + " type: " + deviceId.getType());
        }

        try {
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation = operationDAO.
                                                                                                           getOperationByDeviceAndId(
                                                                                                                   enrolmentId,
                                                                                                                   operationId);
            if (dtoOperation.getType().
                    equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.COMMAND)) {
                org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation commandOperation;
                commandOperation =
                        (org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) commandOperationDAO.
                                                                                                                        getOperation(
                                                                                                                                dtoOperation
                                                                                                                                        .getId());
                dtoOperation.setEnabled(commandOperation.isEnabled());
            } else if (dtoOperation.getType().
                    equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.CONFIG)) {
                dtoOperation = configOperationDAO.getOperation(dtoOperation.getId());
            } else if (dtoOperation.getType().equals(
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.PROFILE)) {
                dtoOperation = profileOperationDAO.getOperation(dtoOperation.getId());
            } else if (dtoOperation.getType().equals(
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.POLICY)) {
                dtoOperation = policyOperationDAO.getOperation(dtoOperation.getId());
            }

            if (dtoOperation == null) {
                throw new OperationManagementException("Operation not found for operation Id:" + operationId +
                                                       " device id:" + deviceId.getId());
            }
            operation = OperationDAOUtil.convertOperation(dtoOperation);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                                                   "operations assigned for '" + deviceId.getType() +
                                                   "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening connection to the data source",
                                                   e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }

        return operation;
    }

    @Override
    public List<? extends Operation> getOperationsByDeviceAndStatus(
            DeviceIdentifier deviceId, Operation.Status status) throws OperationManagementException {
        List<Operation> operations = new ArrayList<>();
        List<org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> dtoOperationList = new ArrayList<>();

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                                                   deviceId.getType() + "' device, which carries the identifier '" +
                                                   deviceId.getId() + "'");
        }

        int enrolmentId = this.getEnrolmentByStatus(deviceId, EnrolmentInfo.Status.ACTIVE);
        if (enrolmentId < 0) {
            throw new OperationManagementException(
                    "Device not found for device id:" + deviceId.getId() + " " + "type:" +
                    deviceId.getType());
        }

        try {
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status dtoOpStatus =
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.valueOf(status.toString());
            dtoOperationList.addAll(commandOperationDAO.getOperationsByDeviceAndStatus(enrolmentId, dtoOpStatus));
            dtoOperationList.addAll(configOperationDAO.getOperationsByDeviceAndStatus(enrolmentId,
                                                                                      org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(profileOperationDAO.getOperationsByDeviceAndStatus(enrolmentId,
                                                                                       org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(policyOperationDAO.getOperationsByDeviceAndStatus(enrolmentId,
                                                                                      org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));

            Operation operation;

            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : dtoOperationList) {
                operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }

        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                                                   "operations assigned for '" + deviceId.getType() +
                                                   "' device '" +
                                                   deviceId.getId() + "' and status:" + status.toString(), e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operations;
    }

    @Override
    public Operation getOperation(int operationId) throws OperationManagementException {
        Operation operation;
        try {
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation = operationDAO.
                                                                                                           getOperation(
                                                                                                                   operationId);
            if (dtoOperation == null) {
                throw new OperationManagementException("Operation not found for given Id:" + operationId);
            }

            if (dtoOperation.getType()
                            .equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.COMMAND)) {
                org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation commandOperation;
                commandOperation =
                        (org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) commandOperationDAO.
                                                                                                                        getOperation(
                                                                                                                                dtoOperation
                                                                                                                                        .getId());
                dtoOperation.setEnabled(commandOperation.isEnabled());
            } else if (dtoOperation.getType().
                    equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.CONFIG)) {
                dtoOperation = configOperationDAO.getOperation(dtoOperation.getId());
            } else if (dtoOperation.getType().equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.
                                                             PROFILE)) {
                dtoOperation = profileOperationDAO.getOperation(dtoOperation.getId());
            } else if (dtoOperation.getType().equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.
                                                             POLICY)) {
                dtoOperation = policyOperationDAO.getOperation(dtoOperation.getId());
            }
            operation = OperationDAOUtil.convertOperation(dtoOperation);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the operation with operation Id '" +
                                                   operationId, e);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operation;
    }

    //    @Override
    //    public Operation getOperationByActivityId(String activity) throws OperationManagementException {
    //        // This parses the operation id from activity id (ex : ACTIVITY_23) and converts to the integer.
    //        Operation operation;
    //        int enrollmentOpMappingId = Integer.parseInt(
    //                activity.replace(DeviceManagementConstants.OperationAttributes.ACTIVITY, ""));
    //        if (enrollmentOpMappingId == 0) {
    //            throw new IllegalArgumentException("Operation ID cannot be null or zero (0).");
    //        }
    //        try {
    //            OperationManagementDAOFactory.openConnection();
    //            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation =
    //                    operationDAO.getOperationFromEnrollment(enrollmentOpMappingId);
    //
    //            if (dtoOperation == null) {
    //                throw new OperationManagementException("Operation not found for given activity Id:" + activity);
    //            }
    //            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status status = dtoOperation.getStatus();
    //            if (dtoOperation.getType().equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.COMMAND)) {
    //                org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation commandOperation;
    //                commandOperation =
    //                        (org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) commandOperationDAO.
    //                                getOperation(dtoOperation.getId());
    //                dtoOperation.setEnabled(commandOperation.isEnabled());
    //            } else if (dtoOperation.getType().
    //                    equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.CONFIG)) {
    //                dtoOperation = configOperationDAO.getOperation(dtoOperation.getId());
    //            } else if (dtoOperation.getType().equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.
    //                    PROFILE)) {
    //                dtoOperation = profileOperationDAO.getOperation(dtoOperation.getId());
    //            } else if (dtoOperation.getType().equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.
    //                    POLICY)) {
    //                dtoOperation = policyOperationDAO.getOperation(dtoOperation.getId());
    //            }
    //            operation = OperationDAOUtil.convertOperation(dtoOperation);
    //            int enrolmentId = operationDAO.getEnrolmentIdFromMappingId(enrollmentOpMappingId);
    //            if (enrolmentId != 0) {
    //                operation.setResponses(operationDAO.getOperationResponses(enrolmentId, operation.getId()));
    //            }
    //
    //            operation.setStatus(Operation.Status.valueOf(status.toString()));
    //            operation.setActivityId(activity);
    //
    //        } catch (SQLException e) {
    //            throw new OperationManagementException("Error occurred while opening a connection to the data source", e);
    //        } catch (OperationManagementDAOException e) {
    //            throw new OperationManagementException("Error occurred while retrieving the operation with activity Id '" +
    //                    activity, e);
    //        } finally {
    //            OperationManagementDAOFactory.closeConnection();
    //        }
    //
    //        //   return this.getOperation(operationId);
    //        return operation;
    //    }

    @Override
    public Activity getOperationByActivityId(String activity) throws OperationManagementException {
        // This parses the operation id from activity id (ex : ACTIVITY_23) and converts to the integer.
        int operationId = Integer.parseInt(
                activity.replace(DeviceManagementConstants.OperationAttributes.ACTIVITY, ""));
        if (operationId == 0) {
            throw new IllegalArgumentException("Operation ID cannot be null or zero (0).");
        }
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivity(operationId);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the operation with activity Id '" +
                                                   activity, e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<Activity> getOperationByActivityIds(List<String> activities)
            throws OperationManagementException {
        List<Integer> operationIds = new ArrayList<>();
        for (String id : activities) {
            int operationId = Integer.parseInt(
                    id.replace(DeviceManagementConstants.OperationAttributes.ACTIVITY, ""));
            if (operationId == 0) {
                throw new IllegalArgumentException("Operation ID cannot be null or zero (0).");
            } else {
                operationIds.add(operationId);
            }
        }

        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivityList(operationIds);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException(
                    "Error occurred while retrieving the operation with activity Id '" + activities
                            .toString(), e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<Operation> getOperationUpdatedAfter(long timestamp) throws OperationManagementException {
        return null;
    }

    @Override
    public List<Activity> getActivitiesUpdatedAfter(long timestamp) throws OperationManagementException {
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivitiesUpdatedAfter(timestamp);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity list changed after a " +
                                                   "given time.", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<Activity> getActivitiesUpdatedAfter(long timestamp, int limit,
                                                    int offset) throws OperationManagementException {
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivitiesUpdatedAfter(timestamp, limit, offset);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity list changed after a " +
                                                   "given time.", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public int getActivityCountUpdatedAfter(long timestamp) throws OperationManagementException {
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivityCountUpdatedAfter(timestamp);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity count changed after a " +
                                                   "given time.", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    private OperationDAO lookupOperationDAO(Operation operation) {

        if (operation instanceof CommandOperation) {
            return commandOperationDAO;
        } else if (operation instanceof ProfileOperation) {
            return profileOperationDAO;
        } else if (operation instanceof ConfigOperation) {
            return configOperationDAO;
        } else if (operation instanceof PolicyOperation) {
            return policyOperationDAO;
        } else {
            return operationDAO;
        }
    }

    private OperationDAO lookupOperationDAO(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation operation) {

        if (operation instanceof org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) {
            return commandOperationDAO;
        } else if (operation instanceof org.wso2.carbon.device.mgt.core.dto.operation.mgt.ProfileOperation) {
            return profileOperationDAO;
        } else if (operation instanceof org.wso2.carbon.device.mgt.core.dto.operation.mgt.ConfigOperation) {
            return configOperationDAO;
        } else {
            return operationDAO;
        }
    }

    private String getUser() {
        return CarbonContext.getThreadLocalCarbonContext().getUsername();
    }

    private boolean isAuthenticationSkippedOperation(Operation operation) {

        //This is to check weather operations are coming from the task related to retrieving device information.
        DeviceTaskManager taskManager = new DeviceTaskManagerImpl();
        if (taskManager.isTaskOperation(operation.getCode())) {
            return true;
        }

        boolean status;
        switch (operation.getCode()) {
            case DeviceManagementConstants.AuthorizationSkippedOperationCodes.POLICY_OPERATION_CODE:
                status = true;
                break;
            case DeviceManagementConstants.AuthorizationSkippedOperationCodes.MONITOR_OPERATION_CODE:
                status = true;
                break;
            default:
                status = false;
        }

        return status;
    }

    private boolean isActionAuthorized(DeviceIdentifier deviceId) {
        boolean isUserAuthorized;
        try {
            isUserAuthorized = DeviceManagementDataHolder.getInstance().getDeviceAccessAuthorizationService().
                    isUserAuthorized(deviceId, DeviceGroupConstants.Permissions.DEFAULT_OPERATOR_PERMISSIONS);
        } catch (DeviceAccessAuthorizationException e) {
            log.error("Error occurred while trying to authorize current user upon the invoked operation", e);
            return false;
        }
        return isUserAuthorized;
    }

    private int getEnrolmentByStatus(DeviceIdentifier deviceId,
                                     EnrolmentInfo.Status status) throws OperationManagementException {
        int enrolmentId;
        try {
            DeviceManagementDAOFactory.openConnection();
            int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
            enrolmentId = deviceDAO.getEnrolmentByStatus(deviceId, status, tenantId);
        } catch (DeviceManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving metadata of '" +
                                                   deviceId.getType() + "' device carrying the identifier '" +
                                                   deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return enrolmentId;
    }

    private boolean isTaskScheduledOperation(Operation operation) {
        TaskConfiguration taskConfiguration = DeviceConfigurationManager.getInstance().getDeviceManagementConfig().
                getTaskConfiguration();
        for (TaskConfiguration.Operation op : taskConfiguration.getOperations()) {
            if (operation.getCode().equals(op.getOperationName())) {
                return true;
            }
        }
        return false;
    }

}