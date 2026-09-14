package main;

import java.util.Scanner;

public class Main {

    public static void main(String[] args){

        AccountsDb accountsDb = new AccountsDb();
        Utility utility = new Utility();
        int id, selection;
        Scanner input = new Scanner(System.in);

        System.out.print("Enter your id: ");
        id = input.nextInt();
        //Input validation here

        System.out.println("Hello, my name is: " + utility.findRep().getName());
        System.out.println("\nID: " + utility.findRep().getId());
        System.out.println("I will be happy to assist you today!");
        System.out.println("How may I help?");
        System.out.println("\n");
        System.out.print("Option 1 (balance)\n" +
                                       "2. Deposit money\n" +
                                                    "Option 3\n");
        selection = input.nextInt();

        switch(selection) {

            case 1: {
                    System.out.println("Your balance is: " + accountsDb.getBalance(id));
                    break;
            }

            case 2: {
                double amount;
                System.out.println("How much would you like to deposit?");
                amount = input.nextDouble();
                utility.deposit(id, amount);
                System.out.println("Your money was successfully deposited!");
                break;
            }
        }

    }

}
