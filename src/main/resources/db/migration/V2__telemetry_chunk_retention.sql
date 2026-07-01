-- V2: 지속 10k 적재 — 청크 사이징 + retention (M2-par 후속: DB 성장 대응)
-- 근거: docs/measurements/M2-par.md — DB 성장이 시간에 따라 drain을 무너뜨림(13k→<10k).
-- 하드웨어: sda3 352GiB 가용. 미압축 청크 실측 942MB/5분청크(힙+인덱스+toast).
--   → 288청크/일 = ~265GiB/일. retention 1일은 ~75%(peak ~82%)로 빡셈 → 12h로.
--   12h = ~133GiB = 352GiB의 ~38%. 디스크 절반 안 넘김(하드웨어 스펙 결정).
--
-- 압축은 뺐다. 실측(docs/measurements/M2-par.md, V2 런): 압축 결과물은 40.56× 로 훌륭하나,
-- 1시간 지난 청크를 디스크에서 읽어 재압축하는 I/O가 단일 포화 HDD(88%→95%+)에서 insert flush와
-- 경합 → flush 105ms→700ms → 큐 200k 포화 → 27분간 5.86M행 드롭(회복 못 함).
-- 단일 HDD에선 지속 무손실 10k 적재와 백그라운드 압축이 상호 배타적. 압축은 텔레메트리 전용
-- 디스크/SSD 또는 티어드 스토리지 생기면 재도입(하드웨어 블록). 지금은 raw 적재 + retention drop만.

-- 1) 청크 5분 — 현재 청크의 인덱스가 shared_buffers(2GB)에 들어가 insert가 평평 유지.
--    기본 7일이면 10k/s에 청크 하나가 수십억 행 → 인덱스가 캐시를 넘겨 insert 저하.
--    5분 = ~3M 행. 새 청크부터 적용(기존 청크는 retention이 정리).
--    검증: V2 런 18:42~19:45 63분간 ~10k/s 평평·드롭 0(V1 하락 소멸).
SELECT set_chunk_time_interval('telemetry', INTERVAL '5 minutes');

-- 2) retention — 12h 지난 청크 drop. 디스크 총량 bound(~133GiB, 352GiB의 ~38%).
--    청크 drop은 I/O가 싸서(압축과 달리) insert flush를 굶기지 않음.
--    12h = 하드웨어 스펙상 결정(디스크 절반 넘기지 않음). 장기 보존은 이후 다운샘플(continuous aggregate)로.
--    잡 주기는 1시간으로 명시: 기본 하루 주기면 12h 지난 청크가 다음 스윕까지 최대 +24h 더 쌓여
--    (12h+24h=36h치 ≈ 400GiB) 디스크 예산을 넘김. 1시간 스윕이면 실사용 ~12h치로 유지.
SELECT alter_job(
    add_retention_policy('telemetry', INTERVAL '12 hours'),
    schedule_interval => INTERVAL '1 hour'
);

-- 3) device UPSERT bloat 억제 — HOT update + 공격적 autovacuum.
--    last_seen_at·status는 인덱스 없음 → 페이지 여유(fillfactor)만 있으면 HOT 성립.
--    HOT이면 새 튜플이 같은 페이지에 들어가 인덱스 갱신 없이 죽은 튜플이 정리됨 → bloat↓.
ALTER TABLE device SET (
    fillfactor = 70,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_cost_delay = 0
);
