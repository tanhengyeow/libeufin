import React, { useState } from 'react';
import { message, Button, Card, Col, Collapse, Row, Tabs } from 'antd';
import './BankAccounts.less';
import AddBankConnectionDrawer from './AddBankConnectionDrawer';
import BankConnectionCard from './BankConnectionCard';

const { TabPane } = Tabs;
const { Panel } = Collapse;

const BankAccounts = () => {
  const [connectionsList, setConnectionsList] = useState([]);
  const [accountsList, setAccountsList] = useState([]);

  const showError = (err) => {
    message.error(String(err));
  };

  const fetchBankConnections = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-connections`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
    })
      .then((response) => {
        if (response.ok) {
          return response.json();
        }
        throw 'Cannot retrieve bank connections';
      })
      .then((response) => {
        setConnectionsList(response);
      })
      .catch((err) => {
        showError(err);
      });
  };

  const fetchBankAccounts = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-accounts`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
    })
      .then((response) => {
        if (response.ok) {
          return response.json();
        }
        throw 'Cannot retrieve bank accounts';
      })
      .then((response) => {
        setAccountsList(response.accounts);
      })
      .catch((err) => {
        showError(err);
      });
  };

  React.useEffect(() => {
    fetchBankConnections();
    fetchBankAccounts();
  }, []);

  const [visible, setVisible] = useState(false);
  const showDrawer = () => {
    setVisible(true);
  };
  const onClose = () => {
    setVisible(false);
    fetchBankConnections();
    fetchBankAccounts();
  };

  const bankAccountsContent =
    accountsList.length > 0 ? (
      <Row gutter={[40, 40]}>
        {accountsList.map((bankAccount) => (
          <Col key={bankAccount['nexusBankAccountId']} span={8}>
            <Card title={bankAccount['nexusBankAccountId']} bordered={false}>
              <p>Holder: {bankAccount['ownerName']}</p>
              <p>IBAN: {bankAccount['iban']}</p>
              <p>BIC: {bankAccount['bic']}</p>
            </Card>
          </Col>
        ))}
      </Row>
    ) : (
      <div style={{ display: 'flex', justifyContent: 'center' }}>
        <b>
          No bank accounts found. Import your bank accounts from a bank
          connection.
        </b>
      </div>
    );

  return (
    <div className="bank-accounts">
      <Tabs defaultActiveKey="1" type="card" size="large">
        <TabPane tab="Your accounts" key="1">
          <Collapse defaultActiveKey="2">
            <Panel header="Bank connections" key="1">
              <div className="buttons-row">
                <Button type="primary" size="middle" onClick={showDrawer}>
                  Add bank connection
                </Button>
                <AddBankConnectionDrawer visible={visible} onClose={onClose} />
              </div>
              <Row gutter={[40, 40]}>
                {connectionsList
                  ? connectionsList.map((bankConnection) => (
                      <Col key={bankConnection['name']} span={8}>
                        <BankConnectionCard
                          type={String(bankConnection['type']).toUpperCase()}
                          name={bankConnection['name']}
                          updateBankAccountsTab={() => fetchBankAccounts()}
                        />
                      </Col>
                    ))
                  : null}
              </Row>
            </Panel>
            <Panel header="Bank accounts" key="2">
              {bankAccountsContent}
            </Panel>
          </Collapse>
        </TabPane>
        <TabPane tab="Recipient accounts" key="2">
          Placeholder
        </TabPane>
      </Tabs>
    </div>
  );
};

export default BankAccounts;
