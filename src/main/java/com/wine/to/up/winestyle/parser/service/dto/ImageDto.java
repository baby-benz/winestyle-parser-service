package com.wine.to.up.winestyle.parser.service.dto;

import lombok.Data;

import java.io.Serializable;
import java.sql.Blob;

/**
 * Передаваемая сущность изображения
 */
@Data
public class ImageDto implements Serializable {
    private Blob image;
}
