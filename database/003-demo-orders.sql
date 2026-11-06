-- Optional fictional demo records. Run explicitly after the three demo accounts exist.
-- No password, account change, deletion, or replacement of existing orders.
INSERT INTO repair_orders
    (student_id, worker_id, location, category, description, status, repair_result, created_at, completed_at)
SELECT student.id,
       CASE WHEN sample.status IN ('PENDING_ASSIGNMENT', 'CANCELLED') THEN NULL ELSE worker.id END,
       sample.location, sample.category, sample.description, sample.status, sample.repair_result,
       '2026-10-25 09:00:00',
       CASE WHEN sample.status = 'COMPLETED' THEN '2026-10-25 10:00:00' ELSE NULL END
FROM users student
JOIN users worker ON worker.username = 'worker01' AND worker.role = 'WORKER'
CROSS JOIN (
    SELECT '演示宿舍 A101' location, 'AIR_CONDITIONING' category,
           '【课程演示】空调启动后不制冷，请检查。' description,
           'PENDING_ASSIGNMENT' status, CAST(NULL AS CHAR(1000)) repair_result
    UNION ALL SELECT '演示教学楼 B202', 'NETWORK', '【课程演示】教室网络插口无法连接。', 'PENDING_REPAIR', NULL
    UNION ALL SELECT '演示图书馆 C303', 'DOORS_FURNITURE', '【课程演示】阅览室门锁卡顿。', 'IN_PROGRESS', NULL
    UNION ALL SELECT '演示实验楼 D404', 'WATER_ELECTRICITY', '【课程演示】照明灯闪烁。', 'PENDING_CONFIRMATION', '已更换灯管，等待学生核对。'
    UNION ALL SELECT '演示体育馆 E505', 'OTHER', '【课程演示】储物柜把手松动。', 'COMPLETED', '已紧固把手并检查使用情况。'
    UNION ALL SELECT '演示食堂 F606', 'WATER_ELECTRICITY', '【课程演示】水龙头滴水，已自行确认无需报修。', 'CANCELLED', NULL
) sample
WHERE student.username = 'student01' AND student.role = 'STUDENT'
  AND NOT EXISTS (
      SELECT 1 FROM repair_orders existing
      WHERE existing.student_id = student.id
        AND existing.location = sample.location AND existing.description = sample.description
  );
