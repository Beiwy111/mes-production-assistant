package com.upc.computer.face;

import java.time.LocalDateTime;
import java.util.List;

public interface FaceStore {
    record Binding(long userId, String subjectId, String imageId, String pendingSubject,
                   String state, long revision, String consentVersion, LocalDateTime consentAt, LocalDateTime updatedAt) { }
    record Cleanup(long id, long userId, String subjectId) { }
    Binding find(long userId);
    boolean beginEnrollment(long userId, String subject);
    void activate(long userId, String subject, String imageId, String consentVersion);
    void enrollmentFailed(long userId, String subject);
    void revoke(long userId);
    int pending(long userId);
    List<Cleanup> cleanups(Long userId);
    void cleaned(long id);
    void cleanupFailed(long id);
    void recover();
    void audit(Long actorId, Long targetId, String event, String result);
}
