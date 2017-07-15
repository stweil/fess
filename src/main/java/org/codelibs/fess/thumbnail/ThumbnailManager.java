/*
 * Copyright 2012-2017 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.thumbnail;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpSession;

import org.codelibs.core.collection.LruHashMap;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.core.misc.Tuple3;
import org.codelibs.fess.Constants;
import org.codelibs.fess.es.client.FessEsClient;
import org.codelibs.fess.es.config.exbhv.ThumbnailQueueBhv;
import org.codelibs.fess.es.config.exentity.ThumbnailQueue;
import org.codelibs.fess.exception.FessSystemException;
import org.codelibs.fess.exception.JobProcessingException;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.codelibs.fess.util.DocumentUtil;
import org.codelibs.fess.util.ResourceUtil;
import org.elasticsearch.index.query.QueryBuilders;
import org.lastaflute.web.util.LaRequestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

public class ThumbnailManager {
    private static final String FESS_THUMBNAIL_PATH = "fess.thumbnail.path";

    private static final String FESS_VAR_PATH = "fess.var.path";

    private static final String NOIMAGE_FILE_SUFFIX = ".txt";

    protected static final String THUMBNAILS_DIR_NAME = "thumbnails";

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailManager.class);

    protected File baseDir;

    private final List<ThumbnailGenerator> generatorList = new ArrayList<>();

    private BlockingQueue<Tuple3<String, String, String>> thumbnailTaskQueue;

    private volatile boolean generating;

    private Thread thumbnailQueueThread;

    protected int thumbnailPathCacheSize = 10;

    protected String imageExtention = "png";

    protected int splitSize = 5;

    protected int thumbnailTaskQueueSize = 10000;

    protected int thumbnailTaskBulkSize = 100;

    protected long thumbnailTaskQueueTimeout = 10 * 1000L;

    protected long noImageExpired = 24 * 60 * 60 * 1000L; // 24 hours

    @PostConstruct
    public void init() {
        final String thumbnailPath = System.getProperty(FESS_THUMBNAIL_PATH);
        if (thumbnailPath != null) {
            baseDir = new File(thumbnailPath);
        } else {
            final String varPath = System.getProperty(FESS_VAR_PATH);
            if (varPath != null) {
                baseDir = new File(varPath, THUMBNAILS_DIR_NAME);
            } else {
                baseDir = ResourceUtil.getThumbnailPath().toFile();
            }
        }
        if (baseDir.mkdirs()) {
            logger.info("Created: " + baseDir.getAbsolutePath());
        }
        if (!baseDir.isDirectory()) {
            throw new FessSystemException("Not found: " + baseDir.getAbsolutePath());
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Thumbnail Directory: " + baseDir.getAbsolutePath());
        }

        thumbnailTaskQueue = new LinkedBlockingQueue<>(thumbnailTaskQueueSize);
        generating = true;
        thumbnailQueueThread = new Thread((Runnable) () -> {
            final List<Tuple3<String, String, String>> taskList = new ArrayList<>();
            while (generating) {
                try {
                    final Tuple3<String, String, String> task = thumbnailTaskQueue.poll(thumbnailTaskQueueTimeout, TimeUnit.MILLISECONDS);
                    if (task == null) {
                        if (!taskList.isEmpty()) {
                            storeQueue(taskList);
                        }
                    } else if (!taskList.contains(task)) {
                        taskList.add(task);
                        if (taskList.size() > thumbnailTaskBulkSize) {
                            storeQueue(taskList);
                        }
                    }
                } catch (final InterruptedException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Interupted task.", e);
                    }
                } catch (final Exception e) {
                    if (generating) {
                        logger.warn("Failed to generate thumbnail.", e);
                    }
                }
            }
            if (!taskList.isEmpty()) {
                storeQueue(taskList);
            }
        }, "ThumbnailGenerator");
        thumbnailQueueThread.start();
    }

    @PreDestroy
    public void destroy() {
        generating = false;
        thumbnailQueueThread.interrupt();
        try {
            thumbnailQueueThread.join(10000);
        } catch (final InterruptedException e) {
            logger.warn("Thumbnail thread is timeouted.", e);
        }
        generatorList.forEach(g -> {
            try {
                g.destroy();
            } catch (final Exception e) {
                logger.warn("Failed to stop thumbnail generator.", e);
            }
        });
    }

    public String getThumbnailPathOption() {
        return "-D" + FESS_THUMBNAIL_PATH + "=" + baseDir.getAbsolutePath();
    }

    protected void storeQueue(final List<Tuple3<String, String, String>> taskList) {
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final SystemHelper systemHelper = ComponentUtil.getSystemHelper();
        final String[] targets = fessConfig.getThumbnailGeneratorTargetsAsArray();
        final List<ThumbnailQueue> list = new ArrayList<>();
        taskList.stream().filter(entity -> entity != null).forEach(task -> {
            for (final String target : targets) {
                final ThumbnailQueue entity = new ThumbnailQueue();
                entity.setGenerator(task.getValue1());
                entity.setThumbnailId(task.getValue2());
                entity.setPath(task.getValue3());
                entity.setTarget(target);
                entity.setCreatedBy(Constants.SYSTEM_USER);
                entity.setCreatedTime(systemHelper.getCurrentTimeAsLong());
                list.add(entity);
            }
        });
        taskList.clear();
        if (logger.isDebugEnabled()) {
            logger.debug("Storing " + list.size() + " thumbnail tasks.");
        }
        final ThumbnailQueueBhv thumbnailQueueBhv = ComponentUtil.getComponent(ThumbnailQueueBhv.class);
        thumbnailQueueBhv.batchInsert(list);
    }

    public int generate() {
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final List<String> idList = new ArrayList<>();
        final ThumbnailQueueBhv thumbnailQueueBhv = ComponentUtil.getComponent(ThumbnailQueueBhv.class);
        thumbnailQueueBhv.selectList(cb -> {
            if (StringUtil.isBlank(fessConfig.getSchedulerTargetName())) {
                cb.query().setTarget_Equal(Constants.DEFAULT_JOB_TARGET);
            } else {
                cb.query().setTarget_InScope(Lists.newArrayList(Constants.DEFAULT_JOB_TARGET, fessConfig.getSchedulerTargetName()));
            }
            cb.query().addOrderBy_CreatedTime_Asc();
            cb.fetchFirst(fessConfig.getPageThumbnailQueueMaxFetchSizeAsInteger());
        }).forEach(entity -> {
            if (logger.isDebugEnabled()) {
                logger.debug("Generating thumbnail: " + entity);
            }
            idList.add(entity.getId());
            final String generatorName = entity.getGenerator();
            try {
                final ThumbnailGenerator generator = ComponentUtil.getComponent(generatorName);
                final File outputFile = new File(baseDir, entity.getPath());
                final File noImageFile = new File(outputFile.getAbsolutePath() + NOIMAGE_FILE_SUFFIX);
                if (!noImageFile.isFile() || System.currentTimeMillis() - noImageFile.lastModified() > noImageExpired) {
                    if (noImageFile.isFile() && !noImageFile.delete()) {
                        logger.warn("Failed to delete " + noImageFile.getAbsolutePath());
                    }
                    if (!generator.generate(entity.getThumbnailId(), outputFile)) {
                        new File(outputFile.getAbsolutePath() + NOIMAGE_FILE_SUFFIX).setLastModified(System.currentTimeMillis());
                    } else {
                        final long interval = fessConfig.getThumbnailGeneratorIntervalAsInteger().longValue();
                        if (interval > 0) {
                            Thread.sleep(interval);
                        }
                    }
                } else if (logger.isDebugEnabled()) {
                    logger.debug("No image file exists: " + noImageFile.getAbsolutePath());
                }
            } catch (final Exception e) {
                logger.warn("Failed to create thumbnail for " + entity, e);
            }
        });
        if (!idList.isEmpty()) {
            thumbnailQueueBhv.queryDelete(cb -> {
                cb.query().setId_InScope(idList);
            });
            thumbnailQueueBhv.refresh();
        }
        return idList.size();
    }

    public boolean offer(final Map<String, Object> docMap) {
        for (final ThumbnailGenerator generator : generatorList) {
            if (generator.isTarget(docMap)) {
                final String path = getImageFilename(docMap);
                final Tuple3<String, String, String> task = generator.createTask(path, docMap);
                if (task != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Add thumbnail task: " + task);
                    }
                    if (!thumbnailTaskQueue.offer(task)) {
                        logger.warn("Failed to add thumbnail task: " + task);
                    }
                    return true;
                }
                return false;
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Thumbnail generator is not found: " + (docMap != null ? docMap.get("url") : docMap));
        }
        return false;
    }

    protected String getImageFilename(final Map<String, Object> docMap) {
        final StringBuilder buf = new StringBuilder(50);
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final String docid = DocumentUtil.getValue(docMap, fessConfig.getIndexFieldDocId(), String.class);
        for (int i = 0; i < docid.length(); i++) {
            if (i > 0 && i % splitSize == 0) {
                buf.append('/');
            }
            buf.append(docid.charAt(i));
        }
        buf.append('.').append(imageExtention);
        return buf.toString();
    }

    public void storeRequest(final String queryId, final List<Map<String, Object>> documentItems) {
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final Map<String, String> dataMap = new HashMap<>(documentItems.size());
        for (final Map<String, Object> docMap : documentItems) {
            final String docid = (String) docMap.get(fessConfig.getIndexFieldDocId());
            final String thumbnailPath = getImageFilename(docMap);
            if (StringUtil.isNotBlank(docid) && StringUtil.isNotBlank(thumbnailPath)) {
                dataMap.put(docid, thumbnailPath);
            }
        }
        final Map<String, Map<String, String>> thumbnailPathCache = getThumbnailPathCache(LaRequestUtil.getRequest().getSession());
        thumbnailPathCache.put(queryId, dataMap);
    }

    public File getThumbnailFile(final String queryId, final String docId) {
        final HttpSession session = LaRequestUtil.getRequest().getSession(false);
        if (session != null) {
            final Map<String, Map<String, String>> thumbnailPathCache = getThumbnailPathCache(session);
            final Map<String, String> dataMap = thumbnailPathCache.get(queryId);
            if (dataMap != null) {
                final String path = dataMap.get(docId);
                final File file = new File(baseDir, path);
                if (file.isFile()) {
                    return file;
                }
            }
        }
        return null;
    }

    private Map<String, Map<String, String>> getThumbnailPathCache(final HttpSession session) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> thumbnailPathCache =
                (Map<String, Map<String, String>>) session.getAttribute(Constants.SCREEN_SHOT_PATH_CACHE);
        if (thumbnailPathCache == null) {
            thumbnailPathCache = new LruHashMap<>(thumbnailPathCacheSize);
            session.setAttribute(Constants.SCREEN_SHOT_PATH_CACHE, thumbnailPathCache);
        }
        return thumbnailPathCache;
    }

    public void add(final ThumbnailGenerator generator) {
        if (generator.isAvailable()) {
            generatorList.add(generator);
        }
    }

    public long purge(final long expiry) {
        if (!baseDir.exists()) {
            return 0;
        }
        try {
            final FilePurgeVisitor visitor = new FilePurgeVisitor(baseDir.toPath(), imageExtention, expiry);
            Files.walkFileTree(baseDir.toPath(), visitor);
            return visitor.getCount();
        } catch (final Exception e) {
            throw new JobProcessingException(e);
        }
    }

    protected static class FilePurgeVisitor implements FileVisitor<Path> {

        protected final long expiry;

        protected long count;

        protected final int maxPurgeSize;

        protected final List<Path> deletedFileList = new ArrayList<>();

        protected final Path basePath;

        protected final String imageExtention;

        protected final FessEsClient fessEsClient;

        protected final FessConfig fessConfig;

        FilePurgeVisitor(final Path basePath, final String imageExtention, final long expiry) {
            this.basePath = basePath;
            this.imageExtention = imageExtention;
            this.expiry = expiry;
            this.fessConfig = ComponentUtil.getFessConfig();
            this.maxPurgeSize = fessConfig.getPageThumbnailPurgeMaxFetchSizeAsInteger();
            this.fessEsClient = ComponentUtil.getFessEsClient();
        }

        protected void deleteFiles() {
            final Map<String, Path> deleteFileMap = new HashMap<>();
            for (final Path path : deletedFileList) {
                final String docId = getDocId(path);
                if (StringUtil.isBlank(docId) || deleteFileMap.containsKey(docId)) {
                    deleteFile(path);
                } else {
                    deleteFileMap.put(docId, path);
                }
            }
            deletedFileList.clear();

            if (!deleteFileMap.isEmpty()) {
                final String docIdField = fessConfig.getIndexFieldDocId();
                fessEsClient.getDocumentList(
                        fessConfig.getIndexDocumentSearchIndex(),
                        fessConfig.getIndexDocumentType(),
                        searchRequestBuilder -> {
                            searchRequestBuilder.setQuery(QueryBuilders.termsQuery(docIdField,
                                    deleteFileMap.keySet().toArray(new String[deleteFileMap.size()])));
                            searchRequestBuilder.setFetchSource(new String[] { docIdField }, StringUtil.EMPTY_STRINGS);
                            return true;
                        }).forEach(m -> {
                    final Object docId = m.get(docIdField);
                    if (docId != null) {
                        deleteFileMap.remove(docId);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Keep thumbnail: " + docId);
                        }
                    }
                });
                ;
                deleteFileMap.values().forEach(v -> deleteFile(v));
                count += deleteFileMap.size();
            }
        }

        protected void deleteFile(final Path path) {
            try {
                Files.delete(path);
                if (logger.isDebugEnabled()) {
                    logger.debug("Delete " + path);
                }
            } catch (IOException e) {
                logger.warn("Failed to delete " + path, e);
            }
        }

        protected String getDocId(final Path file) {
            final String s = file.toUri().toString();
            final String b = basePath.toUri().toString();
            final String id = s.replace(b, StringUtil.EMPTY).replace("." + imageExtention, StringUtil.EMPTY).replace("/", StringUtil.EMPTY);
            if (logger.isDebugEnabled()) {
                logger.debug("Base: " + b + " File: " + s + " DocId: " + id);
            }
            return id;
        }

        public long getCount() {
            if (!deletedFileList.isEmpty()) {
                deleteFiles();
            }
            return count;
        }

        @Override
        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            if (System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > expiry) {
                deletedFileList.add(file);
                if (deletedFileList.size() > maxPurgeSize) {
                    deleteFiles();
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(final Path file, final IOException e) throws IOException {
            if (e != null) {
                logger.warn("I/O exception on " + file, e);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(final Path dir, final IOException e) throws IOException {
            if (e != null) {
                logger.warn("I/O exception on " + dir, e);
            }
            if (dir.toFile().list().length == 0 && !dir.toFile().getName().equals(THUMBNAILS_DIR_NAME)) {
                Files.delete(dir);
            }
            return FileVisitResult.CONTINUE;
        }

    }

    public void setThumbnailPathCacheSize(final int thumbnailPathCacheSize) {
        this.thumbnailPathCacheSize = thumbnailPathCacheSize;
    }

    public void setImageExtention(final String imageExtention) {
        this.imageExtention = imageExtention;
    }

    public void setSplitSize(final int splitSize) {
        this.splitSize = splitSize;
    }

    public void setThumbnailTaskQueueSize(final int thumbnailTaskQueueSize) {
        this.thumbnailTaskQueueSize = thumbnailTaskQueueSize;
    }

    public void setNoImageExpired(final long noImageExpired) {
        this.noImageExpired = noImageExpired;
    }

}
