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
import { message, Button, Drawer, Input, Form, Steps } from 'antd';
const { Step } = Steps;

const layout = {
  labelCol: { span: 4 },
};

const AddBankConnectionDrawer = (props) => {
  const { visible, onClose } = props;
  const [currentStep, setCurrentStep] = useState(0);
  const [printLink, setPrintLink] = useState('');

  const [name, setName] = useState('');
  const [serverURL, setServerURL] = useState('');
  const [hostID, setHostID] = useState('');
  const [partnerID, setPartnerID] = useState('');
  const [userID, setUserID] = useState('');
  const [systemID, setSystemID] = useState('');

  const steps = [{ title: 'Fill up details' }, { title: 'Print document' }];

  const showError = (err) => {
    message.error(String(err));
  };

  const createBankConnection = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-connections`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
        'Content-Type': 'application/json',
      }),
      method: 'POST',
      body: JSON.stringify({
        name: name,
        source: 'new',
        type: 'ebics',
        data: {
          ebicsURL: serverURL,
          hostID: hostID,
          partnerID: partnerID,
          userID: userID,
        },
      }),
    })
      .then((response) => {
        if (!response.ok) {
          throw 'Cannot create bank connection';
        }
      })
      .catch((err) => {
        throw new Error(err);
      });
  };

  const connectBankConnection = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-connections/${name}/connect`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
      method: 'POST',
    })
      .then((response) => {
        if (!response.ok) {
          throw 'Cannot connect bank connection';
        }
      })
      .catch((err) => {
        throw new Error(err);
      });
  };

  const fetchKeyLetter = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-connections/${name}/keyletter`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
    })
      .then((response) => {
        if (response.ok) {
          return response.blob();
        }
        throw 'Cannot retrieve keyletter';
      })
      .then(async (blob) => {
        const pdfLink = URL.createObjectURL(blob);
        setPrintLink(pdfLink);
      })
      .catch((err) => {
        throw new Error(err);
      });
  };

  const updateBankAccounts = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-connections/${name}/fetch-accounts`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
      method: 'POST',
    })
      .then((response) => {
        if (!response.ok) {
          throw 'Cannot update bank accounts';
        }
      })
      .catch((err) => {
        throw new Error(err);
      });
  };

  const next = async () => {
    let isError = true;
    await createBankConnection()
      .then(async () => {
        await connectBankConnection()
          .then(async () => {
            await fetchKeyLetter()
              .then(async () => {
                await updateBankAccounts()
                  .then(() => {
                    isError = false;
                  })
                  .catch((err) => showError(err));
              })
              .catch((err) => showError(err));
          })
          .catch((err) => showError(err));
      })
      .catch((err) => showError(err));

    if (!isError) {
      setServerURL('');
      setHostID('');
      setPartnerID('');
      setUserID('');
      setSystemID('');
      setCurrentStep(currentStep + 1);
    }
  };

  const closeDrawer = () => {
    setCurrentStep(0);
    onClose();
  };

  return (
    <Drawer
      title="Add bank connection"
      placement="right"
      closable={false}
      onClose={onClose}
      visible={visible}
      width={850}
    >
      <div className="steps-row">
        <Steps current={currentStep}>
          {steps.map((item) => (
            <Step key={item.title} title={item.title} />
          ))}
        </Steps>
      </div>
      <div>
        {currentStep < steps.length - 1 ? (
          <Form {...layout} name="basic">
            <Form.Item
              label="Server URL"
              name="Server URL"
              rules={[
                { required: true, message: 'Please input the Server URL!' },
              ]}
            >
              <Input onChange={(e) => setServerURL(e.target.value)} />
            </Form.Item>
            <Form.Item
              label="Name"
              name="Name"
              rules={[
                {
                  required: true,
                  message: 'Please input the name of the bank connection!',
                },
              ]}
            >
              <Input onChange={(e) => setName(e.target.value)} />
            </Form.Item>
            <Form.Item
              label="Host ID"
              name="Host ID"
              rules={[{ required: true, message: 'Please input the Host ID!' }]}
            >
              <Input onChange={(e) => setHostID(e.target.value)} />
            </Form.Item>
            <Form.Item
              label="Partner ID"
              name="Partner ID"
              rules={[
                { required: true, message: 'Please input the Partner ID!' },
              ]}
            >
              <Input onChange={(e) => setPartnerID(e.target.value)} />
            </Form.Item>
            <Form.Item
              label="User ID"
              name="User ID"
              rules={[{ required: true, message: 'Please input the User ID!' }]}
            >
              <Input onChange={(e) => setUserID(e.target.value)} />
            </Form.Item>
            <Form.Item label="System ID" name="System ID">
              <Input onChange={(e) => setSystemID(e.target.value)} />
            </Form.Item>
          </Form>
        ) : (
          <div
            style={{
              fontSize: 24,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
            }}
          >
            <div>Please print out this document and send it to the bank.</div>
            <div>
              <a href={printLink} target="_blank">
                Link to document
              </a>{' '}
            </div>
          </div>
        )}
      </div>
      <div className="steps-action">
        <Button
          style={{ marginRight: '20px' }}
          size="large"
          onClick={() => closeDrawer()}
        >
          Cancel
        </Button>
        {currentStep < steps.length - 1 ? (
          <Button
            style={{ marginRight: '40px' }}
            type="primary"
            size="large"
            onClick={() => next()}
          >
            Next
          </Button>
        ) : (
          <Button
            style={{ marginRight: '40px' }}
            type="primary"
            size="large"
            onClick={() => closeDrawer()}
          >
            Done
          </Button>
        )}
      </div>
    </Drawer>
  );
};

export default AddBankConnectionDrawer;
