package main;

import repository.Accounts;

import java.util.Scanner;

public class Main {

    public static void main(String[] args){

        Accounts accounts = new Accounts();
        Utility utility = new Utility();
        String name, repName;
        int id, selection;
        Scanner input = new Scanner(System.in);

        System.out.println("Enter your id: ");
        name = input.next();
        //Input validation here

        System.out.println("Enter your ID: ");
        id = input.nextInt();


        System.out.println("Hello, my name is: " + utility.findRep());
        System.out.println("I will be happy to assist you today!");
        System.out.println("How may I help?");
        System.out.println("\n");
        System.out.print("Option 1 (balance)\n" +
                                       "Option 2\n" + "Option 3\n");
        selection = input.nextInt();

        switch(selection) {

            case 1: {
                    accounts.getBalance(id);
                    break;
            }

        }

    }

}
