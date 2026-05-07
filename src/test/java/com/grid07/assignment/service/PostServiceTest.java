package com.grid07.assignment.service;

import com.grid07.assignment.dto.CreateCommentRequest;
import com.grid07.assignment.dto.CreatePostRequest;
import com.grid07.assignment.dto.LikePostRequest;
import com.grid07.assignment.entity.*;
import com.grid07.assignment.exception.GuardrailException;
import com.grid07.assignment.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock PostRepository      postRepository;
    @Mock CommentRepository   commentRepository;
    @Mock UserRepository      userRepository;
    @Mock BotRepository       botRepository;
    @Mock GuardrailService    guardrailService;
    @Mock ViralityService     viralityService;
    @Mock NotificationService notificationService;

    @InjectMocks PostService postService;

    // ── createPost ─────────────────────────────────────────────────────────────

    @Test
    void createPost_by_user_saves_and_returns_post() {
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(1L);
        req.setAuthorType(AuthorType.USER);
        req.setContent("Hello");

        User user = new User(1L, "alice", false);
        Post saved = new Post();
        saved.setId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(postRepository.save(any())).thenReturn(saved);

        Post result = postService.createPost(req);

        assertThat(result.getId()).isEqualTo(1L);
        verify(postRepository).save(any(Post.class));
    }

    @Test
    void createPost_throws_when_user_not_found() {
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(99L);
        req.setAuthorType(AuthorType.USER);
        req.setContent("Hello");

        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.createPost(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User not found");
    }

    // ── addComment — human ─────────────────────────────────────────────────────

    @Test
    void addComment_by_human_saves_and_records_virality() {
        Post post = new Post();
        post.setId(1L);
        post.setAuthorType(AuthorType.USER);
        post.setAuthorId(1L);

        Comment saved = new Comment();
        saved.setId(10L);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setAuthorId(1L);
        req.setAuthorType(AuthorType.USER);
        req.setContent("Nice");

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(commentRepository.save(any())).thenReturn(saved);

        Comment result = postService.addComment(1L, req);

        assertThat(result.getId()).isEqualTo(10L);
        verify(viralityService).recordHumanComment(1L);
        verifyNoInteractions(guardrailService);
    }

    // ── addComment — bot ───────────────────────────────────────────────────────

    @Test
    void addComment_by_bot_runs_guardrails_and_records_virality() {
        Post post = new Post();
        post.setId(1L);
        post.setAuthorType(AuthorType.USER);
        post.setAuthorId(1L);

        Comment saved = new Comment();
        saved.setId(20L);

        Bot bot = new Bot(2L, "BotX", "desc");

        CreateCommentRequest req = new CreateCommentRequest();
        req.setAuthorId(2L);
        req.setAuthorType(AuthorType.BOT);
        req.setContent("Reply");

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(commentRepository.save(any())).thenReturn(saved);
        when(botRepository.findById(2L)).thenReturn(Optional.of(bot));

        Comment result = postService.addComment(1L, req);

        assertThat(result.getId()).isEqualTo(20L);
        verify(guardrailService).checkAndReserveBotSlot(1L, 2L, 1L, 0);
        verify(viralityService).recordBotReply(1L);
        verify(notificationService).handleBotInteractionNotification(eq(1L), eq("BotX"), anyString());
    }

    @Test
    void addComment_by_bot_releases_slot_and_cooldown_when_db_fails() {
        Post post = new Post();
        post.setId(1L);
        post.setAuthorType(AuthorType.USER);
        post.setAuthorId(1L);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setAuthorId(2L);
        req.setAuthorType(AuthorType.BOT);
        req.setContent("Reply");

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(commentRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> postService.addComment(1L, req))
            .isInstanceOf(RuntimeException.class);

        verify(guardrailService).releaseBotSlot(1L);
        verify(guardrailService).releaseCooldown(2L, 1L);
    }

    @Test
    void addComment_throws_when_post_not_found() {
        when(postRepository.findById(99L)).thenReturn(Optional.empty());

        CreateCommentRequest req = new CreateCommentRequest();
        req.setAuthorId(1L);
        req.setAuthorType(AuthorType.USER);
        req.setContent("Hi");

        assertThatThrownBy(() -> postService.addComment(99L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Post not found");
    }

    @Test
    void addComment_bot_guardrail_rejection_does_not_save_to_db() {
        Post post = new Post();
        post.setId(1L);
        post.setAuthorType(AuthorType.USER);
        post.setAuthorId(1L);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setAuthorId(2L);
        req.setAuthorType(AuthorType.BOT);
        req.setContent("Reply");

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        doThrow(new GuardrailException("Horizontal cap exceeded"))
            .when(guardrailService).checkAndReserveBotSlot(anyLong(), anyLong(), anyLong(), anyInt());

        assertThatThrownBy(() -> postService.addComment(1L, req))
            .isInstanceOf(GuardrailException.class);

        verify(commentRepository, never()).save(any());
    }

    // ── likePost ───────────────────────────────────────────────────────────────

    @Test
    void likePost_by_user_records_human_like() {
        when(postRepository.existsById(1L)).thenReturn(true);

        LikePostRequest req = new LikePostRequest();
        req.setAuthorId(1L);
        req.setAuthorType(AuthorType.USER);

        postService.likePost(1L, req);

        verify(viralityService).recordHumanLike(1L);
    }

    @Test
    void likePost_by_bot_does_not_record_virality() {
        when(postRepository.existsById(1L)).thenReturn(true);

        LikePostRequest req = new LikePostRequest();
        req.setAuthorId(1L);
        req.setAuthorType(AuthorType.BOT);

        postService.likePost(1L, req);

        verifyNoInteractions(viralityService);
    }

    @Test
    void likePost_throws_when_post_not_found() {
        when(postRepository.existsById(99L)).thenReturn(false);

        LikePostRequest req = new LikePostRequest();
        req.setAuthorId(1L);
        req.setAuthorType(AuthorType.USER);

        assertThatThrownBy(() -> postService.likePost(99L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Post not found");
    }

    // ── depth level ─────────────────────────────────────────────────────────────

    @Test
    void addComment_with_parent_sets_depth_level_as_parent_plus_one() {
        Post post = new Post();
        post.setId(1L);
        post.setAuthorType(AuthorType.USER);
        post.setAuthorId(1L);

        Comment parent = new Comment();
        parent.setId(5L);
        parent.setDepthLevel(3);

        Comment saved = new Comment();
        saved.setId(6L);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setAuthorId(1L);
        req.setAuthorType(AuthorType.USER);
        req.setContent("Reply");
        req.setParentCommentId(5L);

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(commentRepository.findById(5L)).thenReturn(Optional.of(parent));
        when(commentRepository.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            assertThat(c.getDepthLevel()).isEqualTo(4);
            return saved;
        });

        postService.addComment(1L, req);
    }
}
