package com.robustvision.platform.controller;
import com.robustvision.platform.common.*;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.FileAssetRepository;
import com.robustvision.platform.service.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@RestController @RequestMapping("/api/v1/notes/{id}")
public class NoteAssetsController {
 private final NoteService notes;private final FileService files;private final FileAssetRepository assets;private final CurrentUserService current;private final NoteLinkService links;
 public NoteAssetsController(NoteService notes,FileService files,FileAssetRepository assets,CurrentUserService current,NoteLinkService links){this.notes=notes;this.files=files;this.assets=assets;this.current=current;this.links=links;}
 @GetMapping("/backlinks") @PreAuthorize("hasAuthority('note:read')") Object backlinks(@PathVariable String id){notes.get(id);return ApiResponse.ok(links.backlinks(current.requireCurrent().getId(),id));}
 @PostMapping(value="/attachments",consumes="multipart/form-data") @PreAuthorize("hasAuthority('note:write')") @Transactional
 Object upload(@PathVariable String id,@RequestParam("file") MultipartFile upload){
  notes.get(id); if(upload.isEmpty()||upload.getSize()>20*1024*1024)throw new BusinessException(HttpStatus.BAD_REQUEST,"NOTE_ATTACHMENT_SIZE","请选择不超过 20 MB 的图片");
  var asset=files.storeMessageAttachment(upload,current.requireCurrent());
  if(!Set.of("image/png","image/jpeg","image/webp").contains(asset.getContentType())||asset.getScanStatus()!=FileScanStatus.CLEAN)throw new BusinessException(HttpStatus.BAD_REQUEST,"NOTE_IMAGE_INVALID","仅接受通过安全扫描的 PNG、JPEG 或 WEBP 图片");
  String label=asset.getOriginalName();notes.addReference(id,new ApiDtos.AddNoteReferenceRequest("FILE",asset.getId(),label.length()>180?label.substring(0,180):label));
  return ApiResponse.ok(Map.of("id",asset.getId(),"name",asset.getOriginalName()));
 }
 @GetMapping("/attachments/{asset}") @PreAuthorize("hasAuthority('note:read')")
 ResponseEntity<org.springframework.core.io.Resource> image(@PathVariable String id,@PathVariable String asset){var note=notes.get(id);if(note.references().stream().noneMatch(r->r.referenceType().equals("FILE")&&r.referenceId().equals(asset)))throw new BusinessException(HttpStatus.NOT_FOUND,"NOTE_ATTACHMENT_NOT_FOUND","附件不存在");var file=assets.findById(asset).filter(f->f.getOwner().getId().equals(current.requireCurrent().getId())&&f.getScanStatus()==FileScanStatus.CLEAN).orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"NOTE_ATTACHMENT_NOT_FOUND","附件不可用"));return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(file.getContentType())).body(files.asResource(file));}
}
