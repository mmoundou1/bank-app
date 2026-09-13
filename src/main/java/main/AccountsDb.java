package main;

import java.util.LinkedList;

public class AccountsDb {

    LinkedList<Account> id = new LinkedList<>();

    public AccountsDb() {

        Account a = new Account(111, 250000.00);
        id.add(a);
        Account b = new Account(222, 10000.00);
        id.add(b);
        Account c = new Account(222, 350.00);
        id.add(c);
    }

    public double getBalance(int id) {

        Account temp = findAcc(id);

        return temp.getBalance();

    }

    private Account findAcc(int id) {
        Account account = null;

        for(int index = 0; index < this.id.size(); index++) {

            if(id == this.id.get(index).getId())
                account = this.id.get(index);

        }

        return account;
    }


}
