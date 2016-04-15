/*
 * Copyright 2016 Fluo authors (see AUTHORS)
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

package io.fluo.core.util;

import com.google.common.base.Preconditions;

public class Counter {
  private int count;

  public synchronized void increment() {
    count++;
  }

  public synchronized void decrement() {
    Preconditions.checkArgument(count > 0);
    count--;
    notifyAll();
  }

  public synchronized int get() {
    return count;
  }

  public synchronized void waitUntilZero() {
    while (count > 0) {
      try {
        wait();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
