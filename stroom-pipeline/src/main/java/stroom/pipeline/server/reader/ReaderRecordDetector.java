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

package stroom.pipeline.server.reader;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;

import stroom.util.logging.StroomLogger;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import stroom.pipeline.server.errorhandler.ProcessException;
import stroom.pipeline.server.task.RecordDetector;
import stroom.pipeline.server.task.SteppingController;
import stroom.util.spring.StroomScope;

@Component
@Scope(StroomScope.PROTOTYPE)
public class ReaderRecordDetector extends FilterReader implements RecordDetector {
    private static final StroomLogger LOGGER = StroomLogger.getLogger(ReaderRecordDetector.class);
    private static final int MAX_COUNT = 10000;

    private SteppingController controller;

    private long currentStepNo;

    private final char[] buffer = new char[1024];
    private int offset;
    private int length;
    private boolean newStream = true;
    private boolean newRecord;
    private int count;
    private boolean end;

    public ReaderRecordDetector(final Reader reader) {
        super(reader);
    }

    @Override
    public int read(final char buf[], final int off, final int len) throws IOException {
        if (end) {
            return -1;
        }
        if (newStream) {
            currentStepNo = 0;
            controller.resetSourceLocation();

            newStream = false;
        }
        if (newRecord) {
            // Reset
            newRecord = false;
            count = 0;

            currentStepNo++;

            try {
                // Tell the controller that this is the end of a record.
                if (controller.endRecord(null, currentStepNo)) {
                    end = true;
                    return -1;
                }

                return 0;
            } catch (final ProcessException e) {
                throw e;
            } catch (final Exception e) {
                LOGGER.error(e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        if (length - offset == 0) {
            // Fill the buffer.
            length = super.read(buffer, 0, Math.min(buffer.length, len));
        }

        if (length == -1) {
            // The next time anybody tries to read from this reader it will be a
            // new stream.
            newStream = true;
            return -1;
        }

        int i = 0;
        while (i < length - offset) {
            final char c = buffer[offset + i];
            buf[off + i] = c;
            i++;
            count++;

            if (c == '\n' || count >= MAX_COUNT) {
                // The next time anybody tries to read from this reader it will
                // be a new record.
                newRecord = true;
                break;
            }
        }

        offset += i;

        return i;
    }

    @Override
    public void setController(final SteppingController controller) {
        this.controller = controller;
    }
}
