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
import { message, Button, Drawer, Table } from 'antd';

const columns = [
  {
    title: 'Account ID',
    dataIndex: 'offeredAccountId',
  },
  {
    title: 'Owner name',
    dataIndex: 'ownerName',
  },
  {
    title: 'IBAN',
    dataIndex: 'iban',
  },
  {
    title: 'BIC',
    dataIndex: 'bic',
  },
];

const BankConnectionDrawer = (props) => {
  const { visible, onClose, name, updateBankAccountsTab } = props;
  const [printLink, setPrintLink] = useState('');
  const [accountsList, setAccountsList] = useState([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);

  const showError = (err) => {
    message.error(String(err));
  };

  const onSelectChange = (selectedRowKeys) => {
    setSelectedRowKeys(selectedRowKeys);
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
        showError(err);
      });
  };

  const fetchBankAccounts = async () => {
    const authHeader = await window.localStorage.getItem('authHeader');

    await fetch(`/bank-connections/${name}/accounts`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
      }),
    })
      .then((response) => {
        if (!response.ok) {
          throw 'Cannot retrieve bank accounts';
        }
        return response.json();
      })
      .then((response) => {
        setAccountsList(
          response.accounts.map((account, index) => ({
            ...account,
            key: index,
          }))
        );
      })
      .catch((err) => {
        showError(err);
      });
  };

  const importBankAccounts = async () => {
    for (let i = 0; i < selectedRowKeys.length; i++) {
      const { offeredAccountId } = accountsList[i];
      await importBankAccount(offeredAccountId);
    }
    await updateBankAccountsTab(); // refresh bank accounts tab
    onClose();
  };

  const importBankAccount = async (offeredAccountId) => {
    const authHeader = await window.localStorage.getItem('authHeader');
    await fetch(`/bank-connections/${name}/import-account`, {
      headers: new Headers({
        Authorization: `Basic ${authHeader}`,
        'Content-Type': 'application/json',
      }),
      method: 'POST',
      body: JSON.stringify({
        offeredAccountId: offeredAccountId ? offeredAccountId : '',
        nexusBankAccountId: offeredAccountId,
      }),
    })
      .then((response) => {
        if (!response.ok) {
          throw 'Cannot import bank account';
        }
      })
      .catch((err) => {
        showError(err);
      });
  };

  React.useEffect(() => {
    fetchKeyLetter();
    fetchBankAccounts();
  }, []);

  return (
    <Drawer
      title={name}
      placement="right"
      closable={false}
      onClose={onClose}
      visible={visible}
      width={850}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'flex-end',
          marginBottom: 20,
          fontSize: 18,
        }}
      >
        <a href={printLink} target="_blank">
          Print document link
        </a>{' '}
      </div>
      <h2>Import Bank Accounts</h2>
      <Table
        rowSelection={{
          selectedRowKeys,
          onChange: onSelectChange,
        }}
        columns={columns}
        dataSource={accountsList}
      />
      <div className="steps-action">
        <Button
          style={{ marginRight: '20px' }}
          size="large"
          onClick={() => onClose()}
        >
          Cancel
        </Button>
        <Button
          style={{ marginRight: '20px' }}
          size="large"
          onClick={() => importBankAccounts()}
        >
          Import selected
        </Button>
      </div>
    </Drawer>
  );
};

export default BankConnectionDrawer;
