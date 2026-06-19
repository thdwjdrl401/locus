package com.thdwjdrl.locus.core.domain;

/**
 * 위치 권한 상태. 텔레메트리 봉투 {@code permission}.
 *
 * <p>{@code DENIED} 이거나 사용자가 공유를 끈 경우(sharingEnabled=false) 위치를 수집하지 않는다(최소 수집).
 */
public enum PermissionState {
    ALWAYS,
    WHILE_IN_USE,
    DENIED
}
