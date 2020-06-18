import React, { useState } from 'react';
import { Button, Card, Col, Row, Tabs } from 'antd';
import './BankAccounts.less';
// import AddBankConnectionDrawer from './AddBankConnectionDrawer';

const { TabPane } = Tabs;

const BankAccounts = () => {
  const [connectionsList, setConnectionsList] = useState([]);
  const [accountsList, setAccountsList] = useState([]);

  const fetchBankConnections = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-connections`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
    })
      .then((response) => {
        console.log(response);
        if (response.ok) {
          return response.json();
        }
        throw 'Cannot fetch bank connections';
      })
      .then((response) => {
        setConnectionsList(response.bankConnections);
      })
      .catch((err) => {
        console.log(err);
        throw new Error(err);
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
        throw 'Cannot fetch bank accounts';
      })
      .then((response) => {
        setAccountsList(response.accounts);
      })
      .catch((err) => {
        console.log(err);
        throw new Error(err);
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

  return (
    <div className="bank-accounts">
      <Tabs defaultActiveKey="1" type="card" size="large">
        <TabPane tab="Bank connections" key="1">
          <div className="buttons-row">
            <Button type="primary" size="middle" onClick={showDrawer}>
              Add bank connection
            </Button>
            {/* <AddBankConnectionDrawer visible={visible} onClose={onClose} /> */}
            <Button type="primary" size="middle">
              Import from backup
            </Button>
            <Button type="primary" size="middle">
              Reload connections
            </Button>
          </div>
          <Row gutter={[40, 40]}>
            {connectionsList
              ? connectionsList.map((bankConnection) => (
                  <Col span={8}>
                    <Card
                      title={String(bankConnection['type']).toUpperCase()}
                      bordered={false}
                    >
                      <p>Name: {bankConnection['name']}</p>
                    </Card>
                  </Col>
                ))
              : null}
          </Row>
        </TabPane>
        <TabPane tab="Your accounts" key="2">
          <div className="buttons-row">
            <Button type="primary" size="middle">
              Add bank account
            </Button>
          </div>
          <Row gutter={[40, 40]}>
            {accountsList
              ? accountsList.map((bankAccount) => (
                  <Col span={8}>
                    <Card
                      title={String(bankAccount['account']).toUpperCase()}
                      bordered={false}
                    >
                      <p>Holder: {bankAccount['holder']}</p>
                      <p>IBAN: {bankAccount['iban']}</p>
                      <p>BIC: {bankAccount['bic']}</p>
                    </Card>
                  </Col>
                ))
              : null}
          </Row>
        </TabPane>
        <TabPane tab="Recipient accounts" key="3">
          Placeholder
        </TabPane>
      </Tabs>
    </div>
  );
};

export default BankAccounts;
