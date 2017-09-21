/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import Login from './User/Login/Login';
import NotFound from './Error/NotFound';
import BaseLayout from './AppPublisherBase/BaseLayout';
import PlatformCreate from './Platform/PlatformCreate';
import PlatformListing from './Platform/PlatformListing';
import ApplicationCreate from './Application/Create/ApplicationCreate';
import ApplicationListing from './Application/ApplicationListing';
import ApplicationEdit from './Application/Edit/Base/ApplicationEditBaseLayout';

/**
 * Contains all UI components related to Application, Login and Platform
 */

export {Login, BaseLayout, ApplicationCreate, ApplicationListing, PlatformListing, NotFound, PlatformCreate, ApplicationEdit};
