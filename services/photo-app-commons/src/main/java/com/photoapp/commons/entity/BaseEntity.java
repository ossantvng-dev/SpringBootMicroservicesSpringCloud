package com.photoapp.commons.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object o) {
        // Same reference → definitely equal
        if (this == o) return true;

        // Null check → cannot be equal to null
        if (o == null) return false;

        /*
            Use Hibernate.getClass to handle proxy instances correctly.
            This avoids issues where Hibernate creates proxy subclasses.
        */
        if (Hibernate.getClass(this) != Hibernate.getClass(o)) return false;

        // Safe cast after type check
        BaseEntity that = (BaseEntity) o;

        /*
            If either ID is null, the entity is not yet persisted.
            Two transient (new) entities should NEVER be considered equal.
        */
        if (this.id == null || that.id == null) return false;

        // Equality is based solely on the database identifier.
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        /*
            Constant hash code to avoid issues when the ID changes
            from null → assigned (after persistence).
            Ensures stable behavior in hash-based collections.
        */
        return 31;
    }
}
