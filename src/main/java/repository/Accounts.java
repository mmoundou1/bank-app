package repository;

import java.util.LinkedList;

public class Accounts {

    AccountsNodes accountsNodes = new AccountsNodes();
    LinkedList<AccountsNodes> id = new LinkedList<>();

    public Accounts() {}

    void addItems() {

        AccountsNodes accountNode1 = new AccountsNodes();
        AccountsNodes accountNode2 = new AccountsNodes();
        AccountsNodes accountNode3 = new AccountsNodes();

        id.add(accountNode1);
        id.add(accountNode2);
        id.add(accountNode3);

    }

    public double getBalance(int id) {
        return 0.0;
    }

    void findAcc(int id) {
        AccountsNodes accountsNodes = null;

        for(int index = 0; index < this.id.size(); index++) {

            if(id == this.id.get(index).getId())
                accountsNodes = this.id.get(index);

        }
    }

    class AccountsNodes {

        private int id;
        private double balance;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public double getBalance() {
            return balance;
        }

        public void setBalance(double balance) {
            this.balance = balance;
        }

    }

}
