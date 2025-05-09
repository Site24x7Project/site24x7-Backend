package com.example;

public class App {
    public static void main(String[] args) {
        System.out.println("Starting HAR Processor...");
        try {
            HARToDatabase.main(args); // Call the main method directly
        } catch (Exception e) {
            System.err.println("Failed to start application:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}