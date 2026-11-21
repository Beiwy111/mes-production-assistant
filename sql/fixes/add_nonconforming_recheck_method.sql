-- 不合格品处置方式增加「复检」
ALTER TABLE nonconforming_product DROP CHECK chk_nonconforming_method;
ALTER TABLE nonconforming_product ADD CONSTRAINT chk_nonconforming_method
  CHECK (`handle_method` IN ('PENDING','REWORK','SCRAP','CONCESSION','ISOLATE','RECHECK','RETURN'));
