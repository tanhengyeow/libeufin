import React, { useState } from 'react';
import { Tabs } from 'antd';
import PaymentInitiationList from './payments/PaymentInitiationList';
import TransactionsList from './transaction-history/TransactionsList';

import './Activity.less';
const { TabPane } = Tabs;

const Activity = () => {
  const [visible, setVisible] = useState(false);

  const showDrawer = () => {
    setVisible(true);
  };
  const onClose = () => {
    setVisible(false);
  };

  return (
    <div className="activity">
      <Tabs defaultActiveKey="1" type="card" size="large">
        <TabPane tab="Payments" key="1">
          <PaymentInitiationList
            visible={visible}
            onClose={onClose}
            showDrawer={showDrawer}
          />
        </TabPane>
        <TabPane tab="Transaction History" key="2">
          <TransactionsList />
        </TabPane>
        <TabPane tab="Taler View" key="3">
          Taler View
        </TabPane>
      </Tabs>
    </div>
  );
};

export default Activity;
