/*
 This file is part of GNU Taler
 (C) 2020 Taler Systems S.A.

 GNU Taler is free software; you can redistribute it and/or modify it under the
 terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3, or (at your option) any later version.

 GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

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
