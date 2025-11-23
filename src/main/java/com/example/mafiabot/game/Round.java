package com.example.mafiabot.game;


import java.util.Map;

public class Round {
    private final int number;
    private final Map<String, String> moves; // игрок -> действие/цель
    public Round(int number, Map<String,String> moves) {
        this.number = number; this.moves = moves;
    }
    public int getNumber(){ return number; }
    public Map<String,String> getMoves(){ return moves; }
}