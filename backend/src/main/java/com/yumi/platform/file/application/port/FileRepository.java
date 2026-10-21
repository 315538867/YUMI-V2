package com.yumi.platform.file.application.port;

import com.yumi.platform.api.file.FileStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 文件与引用持久化端口。 */
public interface FileRepository {

    void insertFile(FileRecord record);

    Optional<FileRecord> findFile(String fileBusinessId);

    boolean updateStatus(String fileBusinessId, FileStatus status, long expectedRowVersion, Instant now, String operatorId);

    void insertReference(FileReferenceRecord record);

    Optional<FileReferenceRecord> findReference(
            String fileBusinessId, String ownerModule, String ownerType, String ownerBusinessId, String purposeCode);

    boolean deleteReference(String referenceBusinessId);

    List<FileReferenceRecord> findReferencesOfFile(String fileBusinessId);

    int countReferences(String fileBusinessId);

    boolean markDeleted(String fileBusinessId, long expectedRowVersion, Instant now, String operatorId);
}
