/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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
package org.apache.aries.blueprint.pojos;

import org.osgi.service.blueprint.container.BlueprintContainer;

public class PojoRecursive {

    private BlueprintContainer container;
    private String component;

    public PojoRecursive(BlueprintContainer container, String component) {
        this.container = container;
        this.component = component;
    }
    
    public PojoRecursive(BlueprintContainer container, String component, int foo) {
        this.container = container;
        this.component = component;
        container.getComponentInstance(component);
    }
    
    public void setFoo(int foo) {
        container.getComponentInstance(component);
    }
    
    public void init() {    
        container.getComponentInstance(component);
    }
}
