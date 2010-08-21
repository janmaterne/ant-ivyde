/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivyde.eclipse.ui.actions;

import org.apache.ivyde.eclipse.cpcontainer.IvyClasspathContainer;
import org.eclipse.jface.action.IAction;

public class RefreshAction extends IvyDEContainerAction {

    private IvyClasspathContainer cp;

    protected void selectionChanged(IAction a, IvyClasspathContainer ivycp) {
        this.cp = ivycp;
    }

    public void run(IAction action) {
        cp.launchResolve(true, null);
    }

}
