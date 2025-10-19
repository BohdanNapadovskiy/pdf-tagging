package com.netralab.pdfTagging.rest;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class PdfTaggingRequest {
    private String bucketName;
    private String fileName;
    private String folderName;
    private String flattenFileName;
    private String flattenFolderName;
}
