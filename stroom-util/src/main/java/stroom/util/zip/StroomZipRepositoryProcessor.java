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

package stroom.util.zip;

import stroom.util.io.CloseableUtil;
import stroom.util.io.InitialByteArrayOutputStream;
import stroom.util.io.InitialByteArrayOutputStream.BufferPos;
import stroom.util.io.StreamProgressMonitor;
import stroom.util.logging.StroomLogger;
import stroom.util.shared.ModelStringUtil;
import stroom.util.shared.Monitor;
import stroom.util.task.TaskScopeContextHolder;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that reads a nested directory tree of stroom zip files.
 */
public abstract class StroomZipRepositoryProcessor {
    private final StroomLogger LOGGER = StroomLogger.getLogger(StroomZipRepositoryProcessor.class);

    public static final String LOCK_EXTENSION = ".lock";
    public static final String ZIP_EXTENSION = ".zip";
    public static final String ERROR_EXTENSION = ".err";
    public static final String BAD_EXTENSION = ".bad";

    public static final int DEFAULT_MAX_AGGREGATION = 10000;

    /**
     * The max number of parts to send in a zip file
     */
    private int maxAggregation = DEFAULT_MAX_AGGREGATION;

    public static final int DEFAULT_MAX_FILE_SCAN = 10000;

    /**
     * The max number of files to scan before giving up on this iteration
     */
    private int maxFileScan = DEFAULT_MAX_FILE_SCAN;

    public static final long DEFAULT_MAX_STREAM_SIZE = ModelStringUtil.parseNumberString("10G");

    /**
     * The max size of the stream before giving up on this iteration
     */
    private Long maxStreamSize = DEFAULT_MAX_STREAM_SIZE;

    /**
     * Flag set to stop things
     */
    private final Monitor monitor;

    private final Map<String, List<File>> feedToFileMap = new ConcurrentHashMap<String, List<File>>();

    public abstract void processFeedFiles(StroomZipRepository stroomZipRepository, String feed, List<File> fileList);

    public abstract byte[] getReadBuffer();

    public abstract void startExecutor();

    public abstract void stopExecutor(boolean now);

    public abstract void waitForComplete();

    public abstract void execute(String message, Runnable runnable);

    public StroomZipRepositoryProcessor(final Monitor monitor) {
        this.monitor = monitor;
    }

    /**
     * Process a Stroom zip repository,
     *
     * @param stroomZipRepository
     *            The Stroom zip repository to process.
     * @return True is there are more files to process, i.e. we reached our max
     *         file scan limit.
     */
    public boolean process(final StroomZipRepository stroomZipRepository) {
        boolean completedAllFiles = true;

        TaskScopeContextHolder.addContext();
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("process() - Scanning " + stroomZipRepository.getRootDir());
            }
            // Do the threaded work
            startExecutor();

            feedToFileMap.clear();

            // Scan all of the zip files in the repository so that we can map
            // zip files to feeds.
            final Iterable<File> zipFiles = stroomZipRepository.getZipFiles();
            int scanCount = 0;
            for (final File file : zipFiles) {
                scanCount++;

                // Quit once we have hit the max
                if (scanCount > maxFileScan) {
                    completedAllFiles = false;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("process() - Hit scan limit of " + maxFileScan);
                    }
                    break;
                }

                execute(file.getAbsolutePath(), createJobFileScan(stroomZipRepository, file));
            }

            waitForComplete();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("process() - Scanned.  Found Feeds " + feedToFileMap.keySet());
            }

            // Now set the batches together
            final Iterator<Entry<String, List<File>>> iter = feedToFileMap.entrySet().iterator();
            while (iter.hasNext() && !monitor.isTerminated()) {
                final Entry<String, List<File>> entry = iter.next();
                final String feedName = entry.getKey();
                final List<File> fileList = entry.getValue();

                // Sort the map so the items are processed in order
                Collections.sort(fileList, new Comparator<File>() {
                    @Override
                    public int compare(final File arg1, final File arg2) {
                        return arg1.getName().compareTo(arg2.getName());
                    }
                });

                final StringBuilder msg = new StringBuilder();
                msg.append(feedName);
                msg.append(" ");
                msg.append(ModelStringUtil.formatCsv(fileList.size()));
                msg.append(" files (");
                msg.append(fileList.get(0));
                msg.append("...");
                msg.append(fileList.get(fileList.size() - 1));
                msg.append(")");

                execute(msg.toString(), createJobProcessFeedFiles(stroomZipRepository, feedName, fileList));
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("process() - Completed");
            }
        } finally {
            TaskScopeContextHolder.removeContext();
            stopExecutor(false);
        }

        return completedAllFiles;
    }

    private Runnable createJobFileScan(final StroomZipRepository stroomZipRepository, final File file) {
        return () -> {
            if (!monitor.isTerminated()) {
                fileScan(stroomZipRepository, file);
            } else {
                LOGGER.info("run() - Quit File Scan %s", file);
            }
        };
    }

    private Runnable createJobProcessFeedFiles(final StroomZipRepository stroomZipRepository, final String feed,
                                               final List<File> fileList) {
        return () -> {
            if (!monitor.isTerminated()) {
                processFeedFiles(stroomZipRepository, feed, fileList);
            } else {
                LOGGER.info("run() - Quit Feed Aggregation %s", feed);
            }
        };
    }

    /**
     * Peek at the stream to get the header file feed
     */
    private void fileScan(final StroomZipRepository stroomZipRepository, final File file) {
        StroomZipFile stroomZipFile = null;
        try {
            stroomZipFile = new StroomZipFile(file);

            final Set<String> baseNameSet = stroomZipFile.getStroomZipNameSet().getBaseNameSet();

            if (baseNameSet.isEmpty()) {
                stroomZipRepository.addErrorMessage(stroomZipFile, "Unable to find any entry??", true);
                return;
            }

            final String anyBaseName = baseNameSet.iterator().next();

            final InputStream anyHeaderStream = stroomZipFile.getInputStream(anyBaseName, StroomZipFileType.Meta);

            if (anyHeaderStream == null) {
                stroomZipRepository.addErrorMessage(stroomZipFile, "Unable to find header??", true);
                return;
            }

            final HeaderMap headerMap = new HeaderMap();
            headerMap.read(anyHeaderStream, false);

            final String feed = headerMap.get(StroomHeaderArguments.FEED);

            if (!StringUtils.hasText(feed)) {
                stroomZipRepository.addErrorMessage(stroomZipFile, "Unable to find feed in header??", true);
                return;
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("fileScan() - " + file + " belongs to feed " + feed);
                }
            }

            // Small bit of blocking code to add the idea to the map of lists.
            // Most of the work is done above so this should be a problem.
            synchronized (feedToFileMap) {
                List<File> fileList = feedToFileMap.get(feed);
                if (fileList == null) {
                    fileList = new ArrayList<>();
                    feedToFileMap.put(feed, fileList);
                }
                fileList.add(file);
            }

        } catch (final IOException ex) {
            // Unable to open file ... must be bad.
            stroomZipRepository.addErrorMessage(stroomZipFile, ex.getMessage(), true);
            LOGGER.error("fileScan()", ex);

        } finally {
            CloseableUtil.closeLogAndIngoreException(stroomZipFile);
        }
    }

    public Long processFeedFile(final List<? extends StroomStreamHandler> stroomStreamHandlerList,
                                final StroomZipRepository stroomZipRepository, final File file, final StreamProgressMonitor streamProgress,
                                final long startSequence) throws IOException {
        long entrySequence = startSequence;
        StroomZipFile stroomZipFile = null;
        boolean bad = true;

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("processFeedFile() - " + file);
        }

        try {
            stroomZipFile = new StroomZipFile(file);

            for (final String sourceName : stroomZipFile.getStroomZipNameSet().getBaseNameSet()) {
                bad = false;

                final String targetName = StroomFileNameUtil.getFilePathForId(entrySequence++);

                sendEntry(stroomStreamHandlerList, stroomZipFile, sourceName, streamProgress,
                        new StroomZipEntry(null, targetName, StroomZipFileType.Meta));
                sendEntry(stroomStreamHandlerList, stroomZipFile, sourceName, streamProgress,
                        new StroomZipEntry(null, targetName, StroomZipFileType.Context));
                sendEntry(stroomStreamHandlerList, stroomZipFile, sourceName, streamProgress,
                        new StroomZipEntry(null, targetName, StroomZipFileType.Data));
            }
        } catch (final IOException io) {
            stroomZipRepository.addErrorMessage(stroomZipFile, io.getMessage(), bad);
            throw io;
        }

        finally {
            CloseableUtil.close(stroomZipFile);
        }
        return entrySequence;
    }

    public void setMaxStreamSize(final Long maxStreamSize) {
        this.maxStreamSize = maxStreamSize;
    }

    public void setMaxStreamSizeString(final String maxStreamSizeString) {
        this.maxStreamSize = ModelStringUtil.parseNumberString(maxStreamSizeString);
    }

    public Long getMaxStreamSize() {
        return maxStreamSize;
    }

    protected void sendEntry(final List<? extends StroomStreamHandler> requestHandlerList, final StroomZipFile stroomZipFile,
                             final String sourceName, final StreamProgressMonitor streamProgress, final StroomZipEntry targetEntry)
                    throws IOException {
        final InputStream inputStream = stroomZipFile.getInputStream(sourceName, targetEntry.getStroomZipFileType());
        sendEntry(requestHandlerList, inputStream, streamProgress, targetEntry);
    }

    public void sendEntry(final List<? extends StroomStreamHandler> stroomStreamHandlerList, final InputStream inputStream,
                          final StreamProgressMonitor streamProgress, final StroomZipEntry targetEntry) throws IOException {
        if (inputStream != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("sendEntry() - " + targetEntry);
            }
            final byte[] data = getReadBuffer();
            for (final StroomStreamHandler stroomStreamHandler : stroomStreamHandlerList) {
                stroomStreamHandler.handleEntryStart(targetEntry);
            }
            int read = 0;
            long totalRead = 0;
            while ((read = inputStream.read(data)) != -1) {
                totalRead += read;
                streamProgress.progress(read);
                for (final StroomStreamHandler stroomStreamHandler : stroomStreamHandlerList) {
                    stroomStreamHandler.handleEntryData(data, 0, read);
                }
            }
            for (final StroomStreamHandler stroomStreamHandler : stroomStreamHandlerList) {
                stroomStreamHandler.handleEntryEnd();
            }

            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("sendEntry() - " + targetEntry + " " + ModelStringUtil.formatByteSizeString(totalRead));
            }
            if (totalRead == 0) {
                LOGGER.warn("sendEntry() - " + targetEntry + " IS BLANK");
            }
            LOGGER.debug("sendEntry() - %s size is %s", targetEntry, totalRead);

        }
    }

    public void sendEntry(final List<? extends StroomStreamHandler> stroomStreamHandlerList, final HeaderMap headerMap,
                          final StroomZipEntry targetEntry) throws IOException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("sendEntry() - " + targetEntry);
        }
        final byte[] data = getReadBuffer();
        for (final StroomStreamHandler stroomStreamHandler : stroomStreamHandlerList) {
            if (stroomStreamHandler instanceof StroomHeaderStreamHandler) {
                ((StroomHeaderStreamHandler) stroomStreamHandler).handleHeader(headerMap);
            }
            stroomStreamHandler.handleEntryStart(targetEntry);
        }
        final InitialByteArrayOutputStream initialByteArrayOutputStream = new InitialByteArrayOutputStream(data);
        headerMap.write(initialByteArrayOutputStream, false);
        final BufferPos bufferPos = initialByteArrayOutputStream.getBufferPos();
        for (final StroomStreamHandler stroomStreamHandler : stroomStreamHandlerList) {
            stroomStreamHandler.handleEntryData(bufferPos.getBuffer(), 0, bufferPos.getBufferPos());
        }
        for (final StroomStreamHandler stroomStreamHandler : stroomStreamHandlerList) {
            stroomStreamHandler.handleEntryEnd();
        }
    }

    protected void deleteFiles(final StroomZipRepository stroomZipRepository, final List<File> fileList) {
        for (final File file : fileList) {
            stroomZipRepository.delete(new StroomZipFile(file));
        }
    }

    public int getMaxAggregation() {
        return maxAggregation;
    }

    public void setMaxAggregation(final int maxAggregation) {
        this.maxAggregation = maxAggregation;
    }

    public void setMaxFileScan(final int maxFileScan) {
        this.maxFileScan = maxFileScan;
    }

    public int getMaxFileScan() {
        return maxFileScan;
    }
}
