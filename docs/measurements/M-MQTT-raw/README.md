# M-MQTT-raw — 측정 원본

`docs/measurements/M-MQTT.md`의 근거 원본. 런별 하위 폴더(`ramp1`·`sustain1`…)에 관측 로그를 둔다.

수집: 박스에서 `RUN=<라벨> scripts/mqtt-observe.sh`를 부하 직전에 띄우고, 부하(맥 `load/mqtt-ramp.sh`·`load/mqtt-sustain.sh`) 종료 후 Ctrl-C.

각 런 폴더 파일:
- `sys.log` — 브로커 `$SYS`(발행 드롭·클라이언트 수·메시지 수). 경계 A(발행 P 대비 도달 R) 손실 귀속.
- `stream.log` — Redis `XLEN`·컨슈머 `lag`. 경계 C — `lag > MAXLEN(400K)`이면 트림 유실.
- `vmstat.log` — 메모리 궤적(MB, 2s). 스왑 off라 `free` 바닥 = OOM 임박.
- `snap.log` — 앱 카운터(`locus_mqtt_received/dropped`·`locus_ingest_inserted`) + DB count 5s 스냅샷. 3경계 정산 타임라인.
- (선택) emqtt-bench 종료 출력의 sent 총계 = 발행 P.

공통 환경: SUT `i7-6700HQ 4c/8t · 8GB · 5400rpm HDD`(코어 0–5·힙 1.5G 격리) · TimescaleDB 2.17.2-pg16(shared_buffers 2GB) · Redis 7.4(maxmemory 256mb·noeviction·영속화 끔) · Mosquitto 2.0(nofile 1M·익명·영속화 끔) · `INGEST_MODE=stream`·워커 4·`MAXLEN 400K` · **스왑 off**(메모리 벽을 OOM으로 선명하게) · JDK21 G1GC · 부하 emqtt-bench(`emqx/emqtt-bench`, 1만 디바이스×1Hz, QoS1) · 유선 LAN.
