package com.photoapp.users.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "albums")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Album extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(name = "active_album", nullable = false)
    private Boolean activeAlbum = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<Photo> photos = new ArrayList<>();

    public void addPhoto(Photo photo) {
        photos.add(photo);
        photo.setAlbum(this);
    }

    public void removePhoto(Photo photo) {
        photos.remove(photo);
        photo.setAlbum(null);
    }
}
