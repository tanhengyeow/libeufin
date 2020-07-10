import React, { useState } from 'react';
import { Button, Tabs } from 'antd';
import './Activity.less';
import AddPaymentInitiationDrawer from './AddPaymentInitiationDrawer';
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
          <div className="buttons-row">
            <Button type="primary" size="middle" onClick={showDrawer}>
              Add payment initiation
            </Button>
            <AddPaymentInitiationDrawer visible={visible} onClose={onClose} />
          </div>
        </TabPane>
        <TabPane tab="Transaction History" key="2">
          Transaction History
        </TabPane>
        <TabPane tab="Taler View" key="3">
          Taler View
        </TabPane>
      </Tabs>
    </div>
  );
};

export default Activity;
