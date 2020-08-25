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
import { message, Button, Drawer, Input, Form, Select } from 'antd';

const { Option } = Select;

const layout = {
  labelCol: { span: 4 },
};

const AddPaymentInitiationDrawer = (props) => {
  const { visible, onClose, updatePaymentInitiations } = props;

  const [accountsList, setAccountsList] = useState([]);

  const [account, setAccount] = useState('');
  const [name, setName] = useState('');
  const [IBAN, setIBAN] = useState('');
  const [BIC, setBIC] = useState('');
  const [currency, setCurrency] = useState('');
  const [amount, setAmount] = useState('');
  const [subject, setSubject] = useState('');

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

  const createPaymentInitation = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-accounts/${account}/payment-initiations`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
        'Content-Type': 'application/json',
      }),
      method: 'POST',
      body: JSON.stringify({
        name: name,
        iban: IBAN,
        bic: BIC,
        amount: `${currency}:${amount}`,
        subject: subject,
      }),
    })
      .then((response) => {
        if (!response.ok) {
          throw 'Cannot create payment initiation';
        }
      })
      .catch((err) => {
        throw new Error(err);
      });
  };

  React.useEffect(() => {
    fetchBankAccounts();
  }, []);

  const showError = (err) => {
    message.error(String(err));
  };

  const closeDrawer = () => {
    onClose();
  };

  const submitPaymentInitation = async () => {
    let isError = true;
    await createPaymentInitation()
      .then(() => (isError = false))
      .catch((err) => showError(err));
    if (!isError) {
      await updatePaymentInitiations();
      onClose();
    }
  };

  return (
    <Drawer
      title="Add payment initiation"
      placement="right"
      closable={false}
      onClose={onClose}
      visible={visible}
      width={850}
    >
      <div>
        <Form {...layout} name="basic">
          <Form.Item
            label="Account ID"
            name="Account ID"
            rules={[
              { required: true, message: 'Please select your account ID!' },
            ]}
          >
            <Select
              placeholder="Please select your account ID"
              onChange={(e) => setAccount(String(e))}
            >
              {accountsList.map((account) => (
                <Option
                  key={account['nexusBankAccountId']}
                  value={account['nexusBankAccountId']}
                >
                  {account['nexusBankAccountId']}
                </Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item
            label="Name"
            name="Name"
            rules={[
              {
                required: true,
                message:
                  'Please input the name of the legal subject that will receive the payment!',
              },
            ]}
          >
            <Input onChange={(e) => setName(e.target.value)} />
          </Form.Item>
          <Form.Item
            label="IBAN"
            name="IBAN"
            rules={[
              {
                required: true,
                message: 'Please input the IBAN that will receive the payment!',
              },
            ]}
          >
            <Input onChange={(e) => setIBAN(e.target.value)} />
          </Form.Item>
          <Form.Item
            label="BIC"
            name="BIC"
            rules={[
              {
                required: true,
                message: 'Please input the BIC that will receive the payment!',
              },
            ]}
          >
            <Input onChange={(e) => setBIC(e.target.value)} />
          </Form.Item>
          <Form.Item
            label="Currency"
            name="Currency"
            rules={[
              {
                required: true,
                message: 'Please input the currency to send!',
              },
            ]}
          >
            <Input onChange={(e) => setCurrency(e.target.value)} />
          </Form.Item>
          <Form.Item
            label="Amount"
            name="Amount"
            rules={[
              {
                required: true,
                message: 'Please input the amount to send!',
              },
            ]}
          >
            <Input onChange={(e) => setAmount(e.target.value)} />
          </Form.Item>
          <Form.Item
            label="Subject"
            name="Subject"
            rules={[
              {
                required: true,
                message: 'Please input the payment subject!',
              },
            ]}
          >
            <Input onChange={(e) => setSubject(e.target.value)} />
          </Form.Item>
        </Form>
      </div>
      <div className="actions">
        <Button
          style={{ marginRight: '20px' }}
          size="large"
          onClick={() => closeDrawer()}
        >
          Cancel
        </Button>
        <Button
          style={{ marginRight: '40px' }}
          type="primary"
          size="large"
          onClick={() => submitPaymentInitation()}
        >
          Submit
        </Button>
      </div>
    </Drawer>
  );
};

export default AddPaymentInitiationDrawer;
