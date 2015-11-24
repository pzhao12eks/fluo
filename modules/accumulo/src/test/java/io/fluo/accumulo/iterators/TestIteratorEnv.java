/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.accumulo.iterators;

import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.easymock.EasyMock;

public class TestIteratorEnv {
  public static IteratorEnvironment create(IteratorScope scope, boolean fullMajc) {
    IteratorEnvironment iterEnv = EasyMock.createMock(IteratorEnvironment.class);
    EasyMock.expect(iterEnv.getIteratorScope()).andReturn(scope).anyTimes();
    EasyMock.expect(iterEnv.isFullMajorCompaction()).andReturn(fullMajc).anyTimes();
    EasyMock.replay(iterEnv);
    return iterEnv;
  }
}
