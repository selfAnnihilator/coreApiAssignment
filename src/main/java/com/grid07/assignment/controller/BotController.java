package com.grid07.assignment.controller;

import com.grid07.assignment.entity.Bot;
import com.grid07.assignment.repository.BotRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bots")
public class BotController {

    private final BotRepository botRepository;

    public BotController(BotRepository botRepository) {
        this.botRepository = botRepository;
    }

    @PostMapping
    public ResponseEntity<Bot> createBot(@RequestBody Bot bot) {
        return ResponseEntity.status(HttpStatus.CREATED).body(botRepository.save(bot));
    }

    @GetMapping
    public List<Bot> listBots() {
        return botRepository.findAll();
    }
}
