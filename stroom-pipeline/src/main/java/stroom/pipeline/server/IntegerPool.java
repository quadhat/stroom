/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.pipeline.server;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class IntegerPool {
    private Queue<Integer> pool = new ConcurrentLinkedQueue<Integer>();
    private final AtomicInteger count = new AtomicInteger();

    public int get() {
        Integer integer = pool.poll();

        if (integer == null) {
            integer = count.getAndIncrement();
        }

        return integer;
    }

    public void release(final int integer) {
        pool.add(integer);
    }
}
