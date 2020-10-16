package com.wine.to.up.winestyle.parser.service.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.sql.Blob;

/**
 * <pre>
 *     Класс - сущность изображения, содержащий поля:
 *     id - уникальный номер,
 *     image - изображение
 * </pre>
 */
@Setter
@Getter
@ToString
@Entity
@NoArgsConstructor
public class ImageAlcohol {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

//    @Type(type = "org.hibernate.type.BinaryType")
    @Lob
    @Column
    private Blob image;
//    private byte[] image;

    @OneToOne(fetch = FetchType.LAZY,
            cascade = {CascadeType.ALL})
    @JoinTable(
            name = "wine_image",
            joinColumns = @JoinColumn(name = "image_id", referencedColumnName = "id"),
            inverseJoinColumns = @JoinColumn(name = "wine_id", referencedColumnName = "id"))
    private Wine wine;
}
