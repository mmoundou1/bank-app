package main;

import java.util.LinkedList;

public class BankAccountsDb {

    LinkedList<Account> accounts = new LinkedList<>();

    public BankAccountsDb() {

        Account a = new Account("mmoundou", "123Password", 250000.00);
        accounts.add(a);
        Account b = new Account("kcarl", "123Password", 10000.00);
        accounts.add(b);
        Account c = new Account("miaNia", "123Password", 350.00);
        accounts.add(c);
    }

    public Account findAcc(String userName, String password) {

        for(int index = 0; index < accounts.size(); index++) {
            if(accounts.get(index).getUsername().equals(userName) && accounts.get(index).getPassword().equals(password)) {
                return accounts.get(index);
            }
        }
        return null;
    }


}
