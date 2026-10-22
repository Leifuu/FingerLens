package com.life.fingerprint;

// 业务模块共享采集接口，结果统一进入同一证据契约。
public interface Collector { void collect(ItemSink sink) throws Exception; }
