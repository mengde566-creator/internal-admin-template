package com.internaladmin.module.warehouse.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.warehouse.api.WarehouseItemImportApi;
import com.internaladmin.module.warehouse.api.WarehouseItemImportCategory;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 物品导入预览接口；06B 不提供确认写入。 */
@RestController
@RequestMapping("/api/warehouse/item-imports")
@PreAuthorize("hasAuthority('" + PermissionCodes.WAREHOUSE_MASTER_MANAGE + "')")
public class WarehouseItemImportController {
    private final WarehouseItemImportApi service;
    public WarehouseItemImportController(WarehouseItemImportApi service){this.service=service;}
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<WarehouseItemImportApi.WarehouseItemImportJobView> submit(@RequestPart("file") MultipartFile file,
                                                                                   @RequestParam @NotBlank String clientRequestId) throws IOException {
        return ApiResponse.ok(service.submit(user(),clientRequestId,file.getOriginalFilename(),file.getContentType(),file.getInputStream()));
    }
    @GetMapping public ApiResponse<List<WarehouseItemImportApi.WarehouseItemImportJobView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.list(user(),page,size));}
    @GetMapping("/{jobId}") public ApiResponse<WarehouseItemImportApi.WarehouseItemImportJobView> get(@PathVariable String jobId){return ApiResponse.ok(service.get(user(),jobId));}
    @GetMapping("/{jobId}/rows") public ApiResponse<List<WarehouseItemImportApi.WarehouseItemImportRowView>> rows(@PathVariable String jobId,@RequestParam(required=false) WarehouseItemImportCategory category,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size){return ApiResponse.ok(service.rows(user(),jobId,category,page,size));}
    @PostMapping("/{jobId}/reanalyze") public ApiResponse<WarehouseItemImportApi.WarehouseItemImportJobView> reanalyze(@PathVariable String jobId,@RequestParam int revision){return ApiResponse.ok(service.reanalyze(user(),jobId,revision));}
    @PostMapping("/{jobId}/rows/{sourceRowNo}/exclude") public ApiResponse<WarehouseItemImportApi.WarehouseItemImportJobView> excludeRow(@PathVariable String jobId,@PathVariable int sourceRowNo,@RequestParam int revision){return ApiResponse.ok(service.excludeRow(user(),jobId,sourceRowNo,revision));}
    @PostMapping("/{jobId}/cancel") public ApiResponse<WarehouseItemImportApi.WarehouseItemImportJobView> cancel(@PathVariable String jobId,@RequestParam int revision){return ApiResponse.ok(service.cancel(user(),jobId,revision));}
    @GetMapping("/template") public ResponseEntity<byte[]> template(){return file(service.template(user()),"warehouse-item-template.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");}
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam(required=false) String keyword){return file(service.export(user(),keyword),"warehouse-items.csv","text/csv;charset=UTF-8");}
    private static ResponseEntity<byte[]> file(byte[] body,String filename,String type){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.parseMediaType(type));h.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());return ResponseEntity.ok().headers(h).body(body);}
    private static Long user(){Authentication a=SecurityContextHolder.getContext().getAuthentication();if(a==null||!(a.getPrincipal() instanceof Long id)) throw new com.internaladmin.platform.kernel.error.BusinessException(com.internaladmin.platform.kernel.error.ErrorCode.UNAUTHORIZED,"未登录或登录已失效");return id;}
}
