/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint.filemerging;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.state.CheckpointedStateScope;
import org.apache.flink.runtime.state.StreamStateHandle;
import org.apache.flink.runtime.state.TaskStateManager;
import org.apache.flink.runtime.state.filemerging.DirectoryStreamStateHandle;
import org.apache.flink.runtime.state.filemerging.SegmentFileStateHandle;
import org.apache.flink.runtime.state.filesystem.FileMergingCheckpointStateOutputStream;
import org.apache.flink.runtime.state.filesystem.FsCheckpointStorageAccess;

import java.io.Closeable;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * FileMergingSnapshotManager provides an interface to manage files and meta information for
 * checkpoint files with merging checkpoint files enabled. It manages the files for ONE single task
 * in TM, including all subtasks of this single task that is running in this TM. There is one
 * FileMergingSnapshotManager for each job per task manager.
 */
public interface FileMergingSnapshotManager extends Closeable {

    /**
     * Initialize the file system, recording the checkpoint path the manager should work with.
     *
     * <pre>
     * The layout of checkpoint directory:
     * /user-defined-checkpoint-dir
     *     /{job-id} (checkpointBaseDir)
     *         |
     *         + --shared/
     *             |
     *             + --subtask-1/
     *                 + -- merged shared state files
     *             + --subtask-2/
     *                 + -- merged shared state files
     *         + --taskowned/
     *             + -- merged private state files
     *         + --chk-1/
     *         + --chk-2/
     *         + --chk-3/
     * </pre>
     *
     * <p>The reason why initializing directories in this method instead of the constructor is that
     * the FileMergingSnapshotManager itself belongs to the {@link TaskStateManager}, which is
     * initialized when receiving a task, while the base directories for checkpoint are created by
     * {@link FsCheckpointStorageAccess} when the state backend initializes per subtask. After the
     * checkpoint directories are initialized, the managed subdirectories are initialized here.
     *
     * <p>Note: This method may be called several times, the implementation should ensure
     * idempotency, and throw {@link IllegalArgumentException} when any of the path in params change
     * across function calls.
     *
     * @param fileSystem The filesystem to write to.
     * @param checkpointBaseDir The base directory for checkpoints.
     * @param sharedStateDir The directory for shared checkpoint data.
     * @param taskOwnedStateDir The name of the directory for state not owned/released by the
     *     master, but by the TaskManagers.
     * @param writeBufferSize The buffer size for writing files to the file system.
     * @throws IllegalArgumentException thrown if these three paths are not deterministic across
     *     calls.
     */
    void initFileSystem(
            FileSystem fileSystem,
            Path checkpointBaseDir,
            Path sharedStateDir,
            Path taskOwnedStateDir,
            int writeBufferSize)
            throws IllegalArgumentException;

    /**
     * Register a subtask and create the managed directory for shared states.
     *
     * @param subtaskKey the subtask key identifying a subtask.
     * @see #initFileSystem for layout information.
     */
    void registerSubtaskForSharedStates(SubtaskKey subtaskKey);

    /**
     * Unregister a subtask.
     *
     * @param subtaskKey the subtask key identifying a subtask.
     */
    void unregisterSubtask(SubtaskKey subtaskKey);

    /**
     * Create a new {@link FileMergingCheckpointStateOutputStream}. According to the file merging
     * strategy, the streams returned by multiple calls to this function may share the same
     * underlying physical file, and each stream writes to a segment of the physical file.
     *
     * @param subtaskKey The subtask key identifying the subtask.
     * @param checkpointId ID of the checkpoint.
     * @param scope The state's scope, whether it is exclusive or shared.
     * @return An output stream that writes state for the given checkpoint.
     */
    FileMergingCheckpointStateOutputStream createCheckpointStateOutputStream(
            SubtaskKey subtaskKey, long checkpointId, CheckpointedStateScope scope);

    /**
     * Get the managed directory of the file-merging snapshot manager, created in {@link
     * #initFileSystem} or {@link #registerSubtaskForSharedStates}.
     *
     * @param subtaskKey the subtask key identifying the subtask.
     * @param scope the checkpoint scope.
     * @return the managed directory for one subtask in specified checkpoint scope.
     */
    Path getManagedDir(SubtaskKey subtaskKey, CheckpointedStateScope scope);

    /**
     * Get the {@link DirectoryStreamStateHandle} of the managed directory, created in {@link
     * #initFileSystem} or {@link #registerSubtaskForSharedStates}.
     *
     * @param subtaskKey the subtask key identifying the subtask.
     * @param scope the checkpoint scope.
     * @return the {@link DirectoryStreamStateHandle} for one subtask in specified checkpoint scope.
     */
    DirectoryStreamStateHandle getManagedDirStateHandle(
            SubtaskKey subtaskKey, CheckpointedStateScope scope);

    /**
     * Notifies the manager that the checkpoint with the given {@code checkpointId} completed and
     * was committed.
     *
     * @param subtaskKey the subtask key identifying the subtask.
     * @param checkpointId The ID of the checkpoint that has been completed.
     * @throws Exception thrown if anything goes wrong with the listener.
     */
    void notifyCheckpointComplete(SubtaskKey subtaskKey, long checkpointId) throws Exception;

    /**
     * This method is called as a notification once a distributed checkpoint has been aborted.
     *
     * @param subtaskKey the subtask key identifying the subtask.
     * @param checkpointId The ID of the checkpoint that has been completed.
     * @throws Exception thrown if anything goes wrong with the listener.
     */
    void notifyCheckpointAborted(SubtaskKey subtaskKey, long checkpointId) throws Exception;

    /**
     * This method is called as a notification once a distributed checkpoint has been subsumed.
     *
     * @param subtaskKey the subtask key identifying the subtask.
     * @param checkpointId The ID of the checkpoint that has been completed.
     * @throws Exception thrown if anything goes wrong with the listener.
     */
    void notifyCheckpointSubsumed(SubtaskKey subtaskKey, long checkpointId) throws Exception;

    /**
     * Check whether previous state handles could further be reused considering the space
     * amplification.
     *
     * @param stateHandle the handle to be reused.
     */
    boolean couldReusePreviousStateHandle(StreamStateHandle stateHandle);

    /**
     * A callback method which is called when previous state handles are reused by following
     * checkpoint(s).
     *
     * @param checkpointId the checkpoint that reuses the handles.
     * @param stateHandles the handles to be reused.
     */
    void reusePreviousStateHandle(
            long checkpointId, Collection<? extends StreamStateHandle> stateHandles);

    /**
     * Restore and re-register the SegmentFileStateHandles into FileMergingSnapshotManager.
     *
     * @param checkpointId the restored checkpoint id.
     * @param subtaskKey the subtask key identifying the subtask.
     * @param stateHandles the restored segment file handles.
     */
    void restoreStateHandles(
            long checkpointId, SubtaskKey subtaskKey, Stream<SegmentFileStateHandle> stateHandles);

    /**
     * A key identifies a subtask. A subtask can be identified by the operator id, subtask index and
     * the parallelism. Note that this key should be consistent across job attempts.
     */
    final class SubtaskKey {
        final String jobIDString;
        final String operatorIDString;
        final int subtaskIndex;
        final int parallelism;

        /**
         * The cached hash code. Since instances of SubtaskKey are used in HashMap as keys, cached
         * hashcode may help improve the performance.
         */
        final int hashCode;

        public SubtaskKey(JobID jobID, OperatorID operatorID, TaskInfo taskInfo) {
            this(
                    jobID.toHexString(),
                    operatorID.toHexString(),
                    taskInfo.getIndexOfThisSubtask(),
                    taskInfo.getNumberOfParallelSubtasks());
        }

        @VisibleForTesting
        public SubtaskKey(
                String jobIDString, String operatorIDString, int subtaskIndex, int parallelism) {
            this.jobIDString = jobIDString;
            this.operatorIDString = operatorIDString;
            this.subtaskIndex = subtaskIndex;
            this.parallelism = parallelism;
            int hash = jobIDString.hashCode();
            hash = 31 * hash + operatorIDString.hashCode();
            hash = 31 * hash + subtaskIndex;
            hash = 31 * hash + parallelism;
            this.hashCode = hash;
        }

        public static SubtaskKey of(Environment environment) {
            return new SubtaskKey(
                    environment.getJobID(),
                    OperatorID.fromJobVertexID(environment.getJobVertexId()),
                    environment.getTaskInfo());
        }

        /**
         * Generate an unique managed directory name for one subtask.
         *
         * @return the managed directory name.
         */
        public String getManagedDirName() {
            return String.format(
                            "%s_%s_%d_%d_",
                            jobIDString, operatorIDString, subtaskIndex, parallelism)
                    .replaceAll("[^a-zA-Z0-9\\-]", "_");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            SubtaskKey that = (SubtaskKey) o;

            return hashCode == that.hashCode
                    && subtaskIndex == that.subtaskIndex
                    && parallelism == that.parallelism
                    && operatorIDString.equals(that.operatorIDString)
                    && jobIDString.equals(that.jobIDString);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return String.format(
                    "%s-%s(%d/%d)", jobIDString, operatorIDString, subtaskIndex, parallelism);
        }
    }

    /** Space usage statistics of a managed directory. */
    final class SpaceStat {

        AtomicLong physicalFileCount;
        AtomicLong physicalFileSize;

        AtomicLong logicalFileCount;
        AtomicLong logicalFileSize;

        public SpaceStat() {
            this(0, 0, 0, 0);
        }

        public SpaceStat(
                long physicalFileCount,
                long physicalFileSize,
                long logicalFileCount,
                long logicalFileSize) {
            this.physicalFileCount = new AtomicLong(physicalFileCount);
            this.physicalFileSize = new AtomicLong(physicalFileSize);
            this.logicalFileCount = new AtomicLong(logicalFileCount);
            this.logicalFileSize = new AtomicLong(logicalFileSize);
        }

        public void onLogicalFileCreate(long size) {
            logicalFileSize.addAndGet(size);
            logicalFileCount.incrementAndGet();
        }

        public void onLogicalFileDelete(long size) {
            logicalFileSize.addAndGet(-size);
            logicalFileCount.decrementAndGet();
        }

        public void onPhysicalFileUpdate(long size) {
            physicalFileSize.addAndGet(size);
        }

        public void onPhysicalFileCreate() {
            physicalFileCount.incrementAndGet();
        }

        public void onPhysicalFileDelete(long size) {
            physicalFileSize.addAndGet(-size);
            physicalFileCount.decrementAndGet();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SpaceStat spaceStat = (SpaceStat) o;
            return physicalFileCount.get() == spaceStat.physicalFileCount.get()
                    && physicalFileSize.get() == spaceStat.physicalFileSize.get()
                    && logicalFileCount.get() == spaceStat.logicalFileCount.get()
                    && logicalFileSize.get() == spaceStat.logicalFileSize.get();
        }

        @Override
        public String toString() {
            return "SpaceStat{"
                    + "physicalFileCount="
                    + physicalFileCount.get()
                    + ", physicalFileSize="
                    + physicalFileSize.get()
                    + ", logicalFileCount="
                    + logicalFileCount.get()
                    + ", logicalFileSize="
                    + logicalFileSize.get()
                    + '}';
        }
    }
}
