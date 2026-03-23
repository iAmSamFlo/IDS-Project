package test;

import java.io.Console;
import java.io.*;

public class HelloWorld {
    // Dummy to test the runLocally.py script and see that the subprocesses work in parallel
    public static void main(String[] args)
    {
        // Create the console object
        Console cnsl
            = System.console();

        if (cnsl == null) {
            System.out.println(
                "No console available");
            return;
        }

        // Read line
        String str = cnsl.readLine();
        // Print
        System.out.println(
            "You entered : " + str);
    }
}
