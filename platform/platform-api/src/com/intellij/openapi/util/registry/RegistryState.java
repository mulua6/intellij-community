/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.openapi.util.registry;

import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

@State(
  name = "Registry",
  storages = @Storage(file = StoragePathMacros.APP_CONFIG + "/other.xml")
)
public class RegistryState implements BaseComponent, PersistentStateComponent<Element> {
  private static final Logger LOG = Logger.getInstance(RegistryState.class);

  @Override
  public Element getState() {
    return Registry.getInstance().getState();
  }

  @Override
  public void loadState(Element state) {
    Registry.getInstance().loadState(state);
    SortedMap<String, String> userProperties = new TreeMap<String, String>(Registry.getInstance().getUserProperties());
    userProperties.remove("ide.firstStartup");
    if (!userProperties.isEmpty()) {
      LOG.info("Registry values changed by user:");
      for (Map.Entry<String, String> entry : userProperties.entrySet()) {
        LOG.info("  " + entry.getKey() + " = " + entry.getValue());
      }
    }
  }

  @Override
  @NotNull
  public String getComponentName() {
    return "Registry";
  }

  @Override
  public void initComponent() {
  }

  @Override
  public void disposeComponent() {
  }
}