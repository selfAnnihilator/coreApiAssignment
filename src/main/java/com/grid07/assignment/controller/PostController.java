package com.grid07.assignment.controller;

import com.grid07.assignment.dto.CreateCommentRequest;
import com.grid07.assignment.dto.CreatePostRequest;
import com.grid07.assignment.dto.LikePostRequest;
import com.grid07.assignment.entity.Comment;
import com.grid07.assignment.entity.Post;
import com.grid07.assignment.service.PostService;
import com.grid07.assignment.service.ViralityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService     postService;
    private final ViralityService viralityService;

    public PostController(PostService postService, ViralityService viralityService) {
        this.postService     = postService;
        this.viralityService = viralityService;
    }

    @PostMapping
    public ResponseEntity<Post> createPost(@RequestBody CreatePostRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postService.createPost(req));
    }

    @PostMapping("/{postId}/comments")
    public ResponseEntity<Comment> addComment(@PathVariable Long postId,
                                               @RequestBody CreateCommentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postService.addComment(postId, req));
    }

    @PostMapping("/{postId}/like")
    public ResponseEntity<Map<String, Object>> likePost(@PathVariable Long postId,
                                                         @RequestBody LikePostRequest req) {
        postService.likePost(postId, req);
        Long score = viralityService.getViralityScore(postId);
        return ResponseEntity.ok(Map.of("postId", postId, "viralityScore", score));
    }

    @GetMapping("/{postId}/virality")
    public ResponseEntity<Map<String, Object>> getVirality(@PathVariable Long postId) {
        Long score = viralityService.getViralityScore(postId);
        return ResponseEntity.ok(Map.of("postId", postId, "viralityScore", score));
    }
}
