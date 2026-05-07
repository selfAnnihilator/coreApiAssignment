package com.grid07.assignment.service;

import com.grid07.assignment.dto.CreateCommentRequest;
import com.grid07.assignment.dto.CreatePostRequest;
import com.grid07.assignment.dto.LikePostRequest;
import com.grid07.assignment.entity.*;
import com.grid07.assignment.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private final PostRepository      postRepository;
    private final CommentRepository   commentRepository;
    private final UserRepository      userRepository;
    private final BotRepository       botRepository;
    private final GuardrailService    guardrailService;
    private final ViralityService     viralityService;
    private final NotificationService notificationService;

    public PostService(PostRepository postRepository,
                       CommentRepository commentRepository,
                       UserRepository userRepository,
                       BotRepository botRepository,
                       GuardrailService guardrailService,
                       ViralityService viralityService,
                       NotificationService notificationService) {
        this.postRepository      = postRepository;
        this.commentRepository   = commentRepository;
        this.userRepository      = userRepository;
        this.botRepository       = botRepository;
        this.guardrailService    = guardrailService;
        this.viralityService     = viralityService;
        this.notificationService = notificationService;
    }

    @Transactional
    public Post createPost(CreatePostRequest req) {
        validateAuthor(req.getAuthorId(), req.getAuthorType());
        Post post = new Post();
        post.setAuthorId(req.getAuthorId());
        post.setAuthorType(req.getAuthorType());
        post.setContent(req.getContent());
        return postRepository.save(post);
    }

    /**
     * Redis guardrails run BEFORE the DB transaction opens.
     * If a guardrail throws, no DB write is attempted — data integrity preserved.
     */
    @Transactional
    public Comment addComment(Long postId, CreateCommentRequest req) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        int depthLevel = resolveDepthLevel(req.getParentCommentId());

        if (req.getAuthorType() == AuthorType.BOT) {
            Long postOwnerId = resolveHumanOwnerId(post);
            guardrailService.checkAndReserveBotSlot(postId, req.getAuthorId(), postOwnerId, depthLevel);

            Comment comment;
            try {
                comment = saveComment(postId, req, depthLevel);
            } catch (Exception e) {
                // Keep Redis bot-count consistent with DB on failure
                guardrailService.releaseBotSlot(postId);
                throw e;
            }

            viralityService.recordBotReply(postId);

            String botName = botRepository.findById(req.getAuthorId())
                    .map(Bot::getName).orElse("Bot#" + req.getAuthorId());
            notificationService.handleBotInteractionNotification(
                    postOwnerId, botName, "replied to your post");

            return comment;
        }

        Comment comment = saveComment(postId, req, depthLevel);
        viralityService.recordHumanComment(postId);
        return comment;
    }

    @Transactional
    public void likePost(Long postId, LikePostRequest req) {
        if (!postRepository.existsById(postId)) {
            throw new IllegalArgumentException("Post not found: " + postId);
        }
        if (req.getAuthorType() == AuthorType.USER) {
            viralityService.recordHumanLike(postId);
        }
    }

    private Comment saveComment(Long postId, CreateCommentRequest req, int depthLevel) {
        Comment c = new Comment();
        c.setPostId(postId);
        c.setAuthorId(req.getAuthorId());
        c.setAuthorType(req.getAuthorType());
        c.setContent(req.getContent());
        c.setDepthLevel(depthLevel);
        c.setParentCommentId(req.getParentCommentId());
        return commentRepository.save(c);
    }

    private int resolveDepthLevel(Long parentCommentId) {
        if (parentCommentId == null) return 0;
        Comment parent = commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent comment not found: " + parentCommentId));
        return parent.getDepthLevel() + 1;
    }

    private Long resolveHumanOwnerId(Post post) {
        if (post.getAuthorType() == AuthorType.USER) {
            return post.getAuthorId();
        }
        return 0L;
    }

    private void validateAuthor(Long authorId, AuthorType type) {
        if (type == AuthorType.USER) {
            userRepository.findById(authorId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + authorId));
        } else {
            botRepository.findById(authorId)
                    .orElseThrow(() -> new IllegalArgumentException("Bot not found: " + authorId));
        }
    }
}
