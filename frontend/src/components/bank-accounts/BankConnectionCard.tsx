import React, { useState } from 'react';
import { Card } from 'antd';
import BankConnectionDrawer from './BankConnectionDrawer';

const BankConnectionCard = (props) => {
  const { type, name, updateBankAccountsTab } = props;
  const [visible, setVisible] = useState(false);
  const showDrawer = () => {
    setVisible(true);
  };
  const onClose = () => {
    setVisible(false);
  };
  return (
    <>
      <Card title={type} bordered={false} onClick={() => showDrawer()}>
        <p>Name: {name}</p>
      </Card>
      <BankConnectionDrawer
        updateBankAccountsTab={updateBankAccountsTab}
        name={name}
        visible={visible}
        onClose={onClose}
      />
    </>
  );
};

export default BankConnectionCard;
