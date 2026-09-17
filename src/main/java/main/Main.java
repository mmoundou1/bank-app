package main;

import java.util.Scanner;

public class Main {

    public static void main(String[] args){

        Account account;
        RepDb repDb = new RepDb();
        RepService repService = new RepService(repDb);
        BankAccountsDb bankAccountsDb = new BankAccountsDb();
        BankService bankService = new BankService(bankAccountsDb);
        String userName, password;
        int selection;
        Scanner input = new Scanner(System.in);

        System.out.print("Enter your username: ");
        userName = input.next();
        System.out.print("Enter your password: ");
        password = input.next();
        account = bankService.authenticate(userName, password);
        //Input validation here

        System.out.println("Hello, my name is: " + repService.getAvailableRep().getName());
        System.out.println("\nID: " + repService.getAvailableRep().getId());
        System.out.println("I will be happy to assist you today!");
        System.out.println("How may I help?");
        System.out.println("\n");

        System.out.print("1. Check balance\n" +
                                       "2. Deposit money\n" + "3. Withdraw money\n");
        selection = input.nextInt();

        switch(selection) {

            case 1: {
                    System.out.println("Your balance is: " + bankService.getBalance(account));
                    break;
            }

            case 2: {
                double amount;
                System.out.print("How much would you like to deposit? ");
                amount = input.nextDouble();
                bankService.deposit(account, amount);
                System.out.println("\nYour money was successfully deposited!");
                break;
            }

            case 3: {
                boolean result;
                do {
                    double amount;

                    System.out.print("How much would you like to withdraw? ");
                    amount = input.nextDouble();
                    result = bankService.withdraw(account, amount, bankAccountsDb);

                    if (result)
                        System.out.println("Your money was successfully withdrawn!");
                    else
                        System.out.println("There was an issue withdrawing your money. Please try again!");
                }
                while(!result);
                break;

            }

        }

    }

}
