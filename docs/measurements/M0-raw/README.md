# M0 원본 측정 데이터

가공 전 측정 원본. 요약·해석은 [../M0.md](../M0.md) 참조.
이 폴더의 목적은 **재현·검증** — M0.md의 가공값(평균·중앙값)이 어디서 나왔는지 확인할 수 있게 한다.

| 파일 | 내용 |
|---|---|
| `iostat-runD.log` | capacity 런 D 중 박스 `iostat -xzt 1 sda` 전체(1999줄). M0.md의 디스크 지표(%util 96.8·f/s 39·f_await 25.3ms 등) 평균 산출 근거. |
| `k6-output.txt` | capacity 4런(A~D) + baseline 3런 k6 stdout 요약. |

## iostat 정착샘플 평균 재현
M0.md의 디스크 평균은 `iostat-runD.log`에서 부하 구간(f/s>10)만 골라 평균낸 값:
```bash
grep '^sda' iostat-runD.log | awk '$20>10 {
  n++; ws+=$8; wa+=$12; fs+=$20; fa+=$21; aq+=$22; ut+=$23
} END {
  printf "n=%d  w/s=%.1f w_await=%.1f f/s=%.1f f_await=%.1f aqu-sz=%.2f %%util=%.1f\n",
         n, ws/n, wa/n, fs/n, fa/n, aq/n, ut/n
}'
# → n=194  w/s=87.6 w_await=17.6 f/s=39.0 f_await=25.3 aqu-sz=2.62 %util=96.8
```

## 환경
타깃 박스 192.168.219.124:8093, 유선 LAN(RTT avg 0.81ms), 2026-06-29.
하드웨어·자원격리·DB 설정은 [../M0.md](../M0.md) "측정 환경" 참조.
