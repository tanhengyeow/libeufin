import React, { useState } from 'react';
import { message, Button, Select, Table } from 'antd';
import AddPaymentInitiationDrawer from './AddPaymentInitiationDrawer';
import './Activity.less';

const { Option } = Select;

const columns = [
  {
    title: 'ID',
    dataIndex: 'paymentInitiationId',
  },
  {
    title: 'Creditor BIC',
    dataIndex: 'creditorBic',
  },
  {
    title: 'Creditor IBAN',
    dataIndex: 'creditorIban',
  },
  {
    title: 'Creditor Name',
    dataIndex: 'creditorName',
  },
  {
    title: 'Subject',
    dataIndex: 'subject',
  },
  {
    title: 'Preparation Date',
    dataIndex: 'preparationDate',
  },
  {
    title: 'Submission Date',
    dataIndex: 'submissionDate',
  },
  {
    title: 'Submitted',
    dataIndex: 'submitted',
  },
];

const PaymentInitiationList = (props) => {
  const { showDrawer, visible, onClose } = props;
  const [account, setAccount] = useState('');
  const [accountsList, setAccountsList] = useState([]);
  const [paymentInitiationList, setPaymentInitiationList] = useState([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);

  const showError = (err) => {
    message.error(String(err));
  };

  const onSelectChange = (selectedRowKeys) => {
    setSelectedRowKeys(selectedRowKeys);
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
        if (response.accounts.length > 0) {
          setAccount(response.accounts[0]['nexusBankAccountId']);
        }
      })
      .catch((err) => {
        showError(err);
      });
  };

  const fetchPaymentInitiations = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-accounts/${account}/payment-initiations`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
    })
      .then((response) => {
        if (response.ok) {
          return response.json();
        }
        throw `Cannot retrieve payment initiations for ${account}`;
      })
      .then((response) => {
        setPaymentInitiationList(
          response.initiatedPayments.map((initiatedPayment, index) => ({
            ...initiatedPayment,
            key: index,
            submitted: initiatedPayment.submitted ? 'Yes' : 'No',
            submissionDate: initiatedPayment.submissionDate
              ? initiatedPayment.submissionDate
              : '-',
          }))
        );
      })
      .catch((err) => {
        showError(err);
      });
  };

  const submitPaymentInitiations = async () => {
    for (let selectedRow of selectedRowKeys) {
      const { paymentInitiationId } = paymentInitiationList[selectedRow];
      await submitPaymentInitiation(Number(paymentInitiationId));
    }
    await fetchPaymentInitiations(); // refresh table
    onClose();
  };

  const submitPaymentInitiation = async (paymentInitiationId) => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(
      `/bank-accounts/${account}/payment-initiations/${paymentInitiationId}/submit`,
      {
        headers: new Headers({
          Authorization: `Basic ${authHeader}`,
          'Content-Type': 'application/json',
        }),
        method: 'POST',
      }
    )
      .then((response) => {
        if (!response.ok) {
          throw `Cannot submit payment initiation of ID ${paymentInitiationId}`;
        }
      })
      .catch((err) => {
        showError(err);
      });
  };

  React.useEffect(() => {
    fetchBankAccounts();
  }, []);

  React.useEffect(() => {
    if (account !== '') {
      fetchPaymentInitiations();
    }
  }, [account]);

  return (
    <>
      <div className="activity-buttons-row">
        <div className="account-id">
          <div>Account ID: </div>
          <Select
            placeholder={
              accountsList.length > 0
                ? account
                : 'Please select your account ID'
            }
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
        </div>
        <div className="payment-options">
          <Button type="primary" size="middle" onClick={showDrawer}>
            Add payment initiation
          </Button>
          <Button
            type="primary"
            size="middle"
            onClick={() => submitPaymentInitiations()}
          >
            Submit selected payment initiation(s)
          </Button>
        </div>
      </div>
      <AddPaymentInitiationDrawer
        visible={visible}
        onClose={onClose}
        updatePaymentInitiations={() => fetchPaymentInitiations()}
      />
      <Table
        rowSelection={{
          selectedRowKeys,
          onChange: onSelectChange,
        }}
        columns={columns}
        dataSource={paymentInitiationList}
      />
    </>
  );
};

export default PaymentInitiationList;
