package com.upc.computer.face;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class JdbcFaceStore implements FaceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RowMapper<Binding> bindingMapper = (rs, row) -> new Binding(rs.getLong("user_id"),
            rs.getString("subject_id"), rs.getString("image_id"), rs.getString("pending_subject"),
            rs.getString("state"), rs.getLong("revision"), rs.getString("consent_version"),
            time(rs.getTimestamp("consent_at")), time(rs.getTimestamp("updated_at")));
    public JdbcFaceStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }
    private static LocalDateTime time(Timestamp time) { return time == null ? null : time.toLocalDateTime(); }
    @Override public Binding find(long userId) { return find(userId, false); }
    private Binding find(long userId, boolean lock) {
        List<Binding> rows = jdbc.query("SELECT * FROM mes_face_binding WHERE user_id=?" + (lock ? " FOR UPDATE" : ""), bindingMapper, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override public boolean beginEnrollment(long userId, String subject) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            jdbc.update("INSERT IGNORE INTO mes_face_binding(user_id,state,revision,updated_at) VALUES (?,'NONE',0,NOW(6))", userId);
            return jdbc.update("UPDATE mes_face_binding SET state='ENROLLING',pending_subject=?,updated_at=NOW(6) "
                    + "WHERE user_id=? AND state IN ('NONE','ACTIVE')", subject, userId) == 1;
        }));
    }
    @Override public void activate(long userId, String subject, String imageId, String consentVersion) {
        tx.executeWithoutResult(status -> {
            Binding old = find(userId, true);
            if (old == null || !"ENROLLING".equals(old.state()) || !subject.equals(old.pendingSubject())) {
                throw new FaceException(409, "CONCURRENT_CHANGE", "绑定状态已变化，请重新操作");
            }
            queue(userId, old.subjectId(), 0);
            jdbc.update("UPDATE mes_face_binding SET subject_id=?,image_id=?,pending_subject=NULL,state='ACTIVE',"
                    + "revision=revision+1,consent_version=?,consent_at=NOW(6),updated_at=NOW(6) WHERE user_id=?",
                    subject, imageId, consentVersion, userId);
        });
    }
    @Override public void enrollmentFailed(long userId, String subject) {
        tx.executeWithoutResult(status -> {
            // Delay compensation after a timed-out upstream upload to allow an in-flight request to finish.
            queue(userId, subject, 120);
            Binding old = find(userId, true);
            if (old != null && subject.equals(old.pendingSubject())) {
                jdbc.update("UPDATE mes_face_binding SET pending_subject=NULL,state=?,revision=revision+1,updated_at=NOW(6) WHERE user_id=?",
                        old.imageId() == null ? "NONE" : "ACTIVE", userId);
            }
        });
    }
    @Override public void revoke(long userId) {
        tx.executeWithoutResult(status -> {
            Binding old = find(userId, true);
            if (old == null) return;
            if ("ENROLLING".equals(old.state())) throw new FaceException(409, "BUSY", "正在录入人脸，请稍后重试；中断的录入将在五分钟后恢复");
            queue(userId, old.subjectId(), 0);
            queue(userId, old.pendingSubject(), 120);
            jdbc.update("UPDATE mes_face_binding SET subject_id=NULL,image_id=NULL,pending_subject=NULL,state='NONE',"
                    + "revision=revision+1,updated_at=NOW(6) WHERE user_id=?", userId);
        });
    }
    private void queue(long userId, String subject, int delaySeconds) {
        if (subject == null || subject.isBlank()) return;
        CompreFaceClient.requireSubject(subject);
        jdbc.update("INSERT INTO mes_face_cleanup(user_id,subject_id,next_attempt_at,created_at) VALUES (?,?,?,NOW(6)) "
                + "ON DUPLICATE KEY UPDATE subject_id=VALUES(subject_id)", userId, subject, Timestamp.valueOf(LocalDateTime.now().plusSeconds(delaySeconds)));
    }
    @Override public int pending(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_cleanup WHERE user_id=?", Integer.class, userId);
        return count == null ? 0 : count;
    }
    @Override public List<Cleanup> cleanups(Long userId) {
        String sql = "SELECT cleanup_id,user_id,subject_id FROM mes_face_cleanup WHERE next_attempt_at<=NOW(6)"
                + (userId == null ? "" : " AND user_id=?") + " ORDER BY cleanup_id LIMIT 20";
        RowMapper<Cleanup> mapper = (rs, row) -> new Cleanup(rs.getLong(1), rs.getLong(2), rs.getString(3));
        return userId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, userId);
    }
    @Override public void cleaned(long id) { jdbc.update("DELETE FROM mes_face_cleanup WHERE cleanup_id=?", id); }
    @Override public void cleanupFailed(long id) {
        jdbc.update("UPDATE mes_face_cleanup SET attempts=attempts+1,next_attempt_at=? WHERE cleanup_id=?", Timestamp.valueOf(LocalDateTime.now().plusSeconds(60)), id);
    }
    @Override public void recover() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<Long> interrupted = jdbc.queryForList("SELECT user_id FROM mes_face_binding WHERE state='ENROLLING' AND updated_at<? LIMIT 50", Long.class, Timestamp.valueOf(cutoff));
        for (Long userId : interrupted) tx.executeWithoutResult(status -> {
            Binding row = find(userId, true);
            if (row != null && "ENROLLING".equals(row.state()) && row.updatedAt().isBefore(cutoff)) {
                enrollmentFailed(userId, row.pendingSubject());
                audit(null, userId, "ENROLL_RECOVER", "INTERRUPTED");
            }
        });
        List<Long> orphaned = jdbc.queryForList("SELECT f.user_id FROM mes_face_binding f LEFT JOIN `user` u ON f.user_id=u.user_id "
                + "WHERE u.user_id IS NULL AND f.state='ACTIVE' LIMIT 50", Long.class);
        for (Long userId : orphaned) { revoke(userId); audit(null, userId, "ACCOUNT_REMOVED", "REVOKED"); }
    }
    @Override public void audit(Long actorId, Long targetId, String event, String result) {
        jdbc.update("INSERT INTO mes_face_audit(actor_user_id,target_user_id,event_type,result_code,created_at) VALUES (?,?,?,?,NOW(6))",
                actorId, targetId, event, result);
    }
}
