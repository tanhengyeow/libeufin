import React, { useState } from 'react';
import './Home.less';
import { message, Button, Card, Col, Row } from 'antd';
import { RightOutlined } from '@ant-design/icons';

import history from '../../history';

const Home = () => {
  const [accountsList, setAccountsList] = useState([]);

  const showError = (err) => {
    message.error(String(err));
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
    fetchBankAccounts();
  }, []);

  const clickHomeBankAccounts = () => {
    history.push('/bank-accounts');
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
    ) : null;

  return (
    <>
      <div className="home-bank-accounts">
        <h1 style={{ marginRight: 10 }}>Bank Accounts</h1>
        <Button
          type="primary"
          shape="circle"
          icon={<RightOutlined />}
          size="large"
          onClick={() => clickHomeBankAccounts()}
        />
      </div>
      {bankAccountsContent}
    </>
  );
};

export default Home;
