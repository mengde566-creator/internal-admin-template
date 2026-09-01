package com.internaladmin.module.file.api;

/** 受权读取结果；正文只在服务端受信业务链中返回。 */
public record ControlledDocumentRead(ControlledDocumentAsset metadata, byte[] content) {
}
