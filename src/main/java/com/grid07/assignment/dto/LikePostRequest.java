package com.grid07.assignment.dto;

import com.grid07.assignment.entity.AuthorType;

public class LikePostRequest {
    private Long authorId;
    private AuthorType authorType;

    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public AuthorType getAuthorType() { return authorType; }
    public void setAuthorType(AuthorType authorType) { this.authorType = authorType; }
}
